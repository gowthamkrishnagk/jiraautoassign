package com.jira.autoassign.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.config.JiraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    private final RestTemplate restTemplate;
    private final JiraProperties props;

    public JiraClient(RestTemplate restTemplate, JiraProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    private HttpHeaders authHeaders() {
        String credentials = props.getEmail() + ":" + props.getApiToken();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    public String getAccountId(String email) {
        String url = UriComponentsBuilder
                .fromHttpUrl(props.getUrl() + "/rest/api/3/user/search")
                .queryParam("query", email)
                .toUriString();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

        JsonNode users = response.getBody();
        if (users == null || !users.isArray() || users.isEmpty()) {
            throw new RuntimeException("No Jira user found for email: " + email);
        }
        return users.get(0).get("accountId").asText();
    }

    public List<JsonNode> getTickets() {
        String jql;

        if (props.getCustomJql() != null && !props.getCustomJql().isBlank()) {
            // Use custom JQL exactly as configured in application.properties
            jql = props.getCustomJql();
            log.info("Using custom JQL: {}", jql);
        } else {
            // Build JQL from individual filter properties
            List<String> conditions = new ArrayList<>();
            conditions.add("project = " + props.getProjectKey());

            if (props.isOnlyUnassigned()) {
                conditions.add("assignee = EMPTY");
            }

            if (props.getTargetStatuses() != null && !props.getTargetStatuses().isEmpty()) {
                String statusList = String.join(", ",
                        props.getTargetStatuses().stream().map(s -> "\"" + s + "\"").toList());
                conditions.add("status in (" + statusList + ")");
            }

            if (props.getTargetIssueTypes() != null && !props.getTargetIssueTypes().isEmpty()) {
                String typeList = String.join(", ",
                        props.getTargetIssueTypes().stream().map(t -> "\"" + t + "\"").toList());
                conditions.add("issuetype in (" + typeList + ")");
            }

            if (props.getTargetLabels() != null && !props.getTargetLabels().isEmpty()) {
                String labelList = String.join(", ",
                        props.getTargetLabels().stream().map(l -> "\"" + l + "\"").toList());
                conditions.add("labels in (" + labelList + ")");
            }

            jql = String.join(" AND ", conditions) + " ORDER BY created ASC";
            log.info("Using built JQL: {}", jql);
        }

        // Use expand() to safely encode JQL without double-encoding special characters
        URI uri = UriComponentsBuilder
                .fromHttpUrl(props.getUrl() + "/rest/api/3/search/jql")
                .queryParam("jql", "{jql}")
                .queryParam("maxResults", 100)
                .queryParam("fields", "summary,assignee,status,issuetype,labels")
                .build()
                .expand(jql)
                .encode()
                .toUri();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

        List<JsonNode> tickets = new ArrayList<>();
        if (response.getBody() != null && response.getBody().has("issues")) {
            response.getBody().get("issues").forEach(tickets::add);
        }
        return tickets;
    }

    public boolean assignTicket(String issueKey, String accountId) {
        String url = props.getUrl() + "/rest/api/3/issue/" + issueKey + "/assignee";

        HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("accountId", accountId), authHeaders());

        // Retry up to 3 times on 429 rate limit with increasing delays
        int[] retryDelaysMs = {5000, 15000, 30000};
        for (int attempt = 0; attempt <= retryDelaysMs.length; attempt++) {
            try {
                ResponseEntity<Void> response = restTemplate.exchange(
                        url, HttpMethod.PUT, request, Void.class);
                return response.getStatusCode() == HttpStatus.NO_CONTENT;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < retryDelaysMs.length) {
                    log.warn("Rate limited by Jira. Waiting {}ms before retry {}/{}...",
                            retryDelaysMs[attempt], attempt + 1, retryDelaysMs.length);
                    try { Thread.sleep(retryDelaysMs[attempt]); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    log.error("Failed to assign {}: {}", issueKey, e.getMessage());
                    return false;
                }
            }
        }
        return false;
    }

    /** Small delay between ticket assignments to avoid hitting Jira rate limits */
    public void pauseBetweenAssignments() {
        try {
            Thread.sleep(1000); // 1 second between each assignment
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
