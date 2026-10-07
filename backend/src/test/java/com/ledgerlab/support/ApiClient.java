package com.ledgerlab.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Thin MockMvc wrapper that returns status, headers and parsed JSON. */
@TestComponent
public class ApiClient {

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;

    public ApiClient(MockMvc mvc, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
    }

    public record Response(int status, JsonNode body, MvcResult raw) {
        public String header(String name) {
            return raw.getResponse().getHeader(name);
        }

        public String code() {
            return body == null || body.get("code") == null ? null : body.get("code").asText();
        }

        public String text() {
            try {
                return raw.getResponse().getContentAsString(StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public Response get(String path, String token) {
        return perform(withAuth(MockMvcRequestBuilders.get(path), token));
    }

    public Response post(String path, String token, Object body) {
        return post(path, token, body, null);
    }

    public Response post(String path, String token, Object body, String idempotencyKey) {
        MockHttpServletRequestBuilder request = withAuth(MockMvcRequestBuilders.post(path), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return perform(request);
    }

    public Response patch(String path, String token, Object body) {
        return perform(withAuth(MockMvcRequestBuilders.patch(path), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    public Response perform(MockHttpServletRequestBuilder request) {
        try {
            MvcResult result = mvc.perform(request).andReturn();
            String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8).trim();
            JsonNode body = content.startsWith("{") || content.startsWith("[") ? objectMapper.readTree(content) : null;
            return new Response(result.getResponse().getStatus(), body, result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String json(Object body) {
        try {
            return body instanceof String s ? s : objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }
}
