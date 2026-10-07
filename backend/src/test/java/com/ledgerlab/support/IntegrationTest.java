package com.ledgerlab.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for integration tests against a real PostgreSQL started by Testcontainers. The Spring
 * context (and container) is shared across test classes. Tests isolate themselves by creating a
 * fresh organization through {@link TestFixtures} rather than truncating tables, which the ledger
 * triggers forbid by design.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, TestFixtures.class, ApiClient.class})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected TestFixtures fixtures;

    @Autowired
    protected ApiClient api;

    @Autowired
    protected JdbcTemplate jdbc;
}
