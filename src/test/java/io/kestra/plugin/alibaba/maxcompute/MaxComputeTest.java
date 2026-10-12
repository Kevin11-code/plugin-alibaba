package io.kestra.plugin.alibaba.maxcompute;

import com.aliyun.odps.Column;
import com.aliyun.odps.Instance;
import com.aliyun.odps.Odps;
import com.aliyun.odps.OdpsType;
import com.aliyun.odps.TableSchema;
import com.aliyun.odps.data.ArrayRecord;
import com.aliyun.odps.tunnel.io.ProtobufRecordPack;
import com.aliyun.odps.task.SQLTask;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import java.time.Duration;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@KestraTest
class MaxComputeTest {
    @Inject
    private RunContextFactory runContextFactory;
    private static WireMockServer server;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort().templatingEnabled(true));
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/projects/analytics/instances"))
            .willReturn(aResponse().withStatus(200).withHeader("Location", "/projects/analytics/instances/instance-1")
                .withBody("<Instance><Tasks><Task Type=\"SQL\"><Name>{{xPath request.body '//SQL/Name/text()'}}</Name><Status>Success</Status><Result/></Task></Tasks></Instance>")
                .withTransformers("response-template")));
        server.stubFor(get(urlPathEqualTo("/projects/analytics/instances/instance-1"))
            .willReturn(aResponse().withHeader("Content-Type", "application/xml").withBody("""
                <Instance><Status>Terminated</Status><Tasks><Task Type="SQL"><Name>sql</Name><Status>Success</Status></Task></Tasks></Instance>
                """)));
    }

    private Query.QueryBuilder<?, ?> query(FetchType mode) {
        return Query.builder().id("query").type(Query.class.getName())
            .accessKeyId(Property.ofValue("ak")).accessKeySecret(Property.ofValue("sk"))
            .endpointOverride(Property.ofValue("http://localhost:" + server.port()))
            .project(Property.ofValue("analytics")).sql(Property.ofValue("SELECT id FROM t"))
            .fetchType(Property.ofValue(mode)).tunnelEndpoint(Property.ofValue("http://localhost:" + server.port()));
    }

    private void stubResult(long... values) throws Exception {
        var schema = new TableSchema();
        schema.addColumn(new Column("id", OdpsType.BIGINT));
        var pack = new ProtobufRecordPack(schema);
        for (var value : values) {
            var row = new ArrayRecord(schema);
            row.setBigint(0, value);
            pack.append(row);
        }
        pack.complete();
        server.stubFor(post(urlPathEqualTo("/projects/analytics/instances/instance-1"))
            .willReturn(aResponse().withBody("""
                {"DownloadID":"download-1","Status":"Normal","RecordCount":%s,
                "Schema":{"columns":[{"name":"id","type":"bigint"}]}}
                """.formatted(values.length))));
        server.stubFor(get(urlPathEqualTo("/projects/analytics/instances/instance-1"))
            .withQueryParam("downloadid", equalTo("download-1"))
            .willReturn(aResponse().withBody(pack.getProtobufStream().toByteArray())));
    }

    @Test
    void querySubmitsAndWaitsAtLocalEndpoint() throws Exception {
        var output = query(FetchType.NONE).build().run(runContextFactory.of(Map.of()));
        assertEquals("instance-1", output.getInstanceId());
        server.verify(postRequestedFor(urlPathEqualTo("/projects/analytics/instances"))
            .withRequestBody(containing("SELECT id FROM t")));
    }

    @Test
    void queryStreamsStoresAndAppliesTunnelOverride() throws Exception {
        stubResult(9L, 10L);
        var rc = runContextFactory.of(Map.of());
        var output = query(FetchType.STORE).build().run(rc);
        assertEquals(2L, output.getSize());
        assertEquals(null, output.getRows());
        var stored = new ArrayList<Object>();
        try (var input = rc.storage().getFile(output.getUri())) {
            FileSerde.read(input, stored::add);
        }
        assertEquals(2, stored.size());
        assertEquals(10, ((Number) ((Map<?, ?>) stored.get(1)).get("id")).intValue());
    }

    @Test
    void queryFetchesFirstRowWithoutReadingWholeResult() throws Exception {
        stubResult(9L);
        var output = query(FetchType.FETCH_ONE).build().run(runContextFactory.of(Map.of()));
        assertEquals(9L, output.getRow().get("id"));
    }

    private Trigger.TriggerBuilder<?, ?> trigger(FetchType mode, String id) {
        return Trigger.builder().id(id).type(Trigger.class.getName())
            .accessKeyId(Property.ofValue("ak")).accessKeySecret(Property.ofValue("sk"))
            .endpointOverride(Property.ofValue("http://localhost:" + server.port())).project(Property.ofValue("analytics"))
            .sql(Property.ofExpression("SELECT id FROM t WHERE id > '{{ trigger.watermark }}'"))
            .watermarkField(Property.ofValue("id")).initialWatermark(Property.ofValue("0"))
            .fetchType(Property.ofValue(mode)).tunnelEndpoint(Property.ofValue("http://localhost:" + server.port()));
    }

    @Test
    void realTriggerPersistsNumericWatermarksAcrossTwoPollsInFetchAndStore() throws Exception {
        for (var mode : List.of(FetchType.FETCH, FetchType.STORE)) {
            var id = "trigger-" + UUID.randomUUID();
            var task = trigger(mode, id).build();
            var ctx = TriggerContext.builder().tenantId("main").namespace("company.team").flowId("mc_flow").triggerId(id).build();
            var rc = runContextFactory.of(Map.of("flow", Map.of("namespace", ctx.getNamespace(), "id", ctx.getFlowId(), "tenantId", "main")));
            runContextFactory.initializer().forScheduler((DefaultRunContext) rc, ctx, task);
            var cond = ConditionContext.builder().runContext(rc)
                .flow(Flow.builder().id(ctx.getFlowId()).namespace(ctx.getNamespace()).revision(1).build()).build();
            stubResult(9L, 10L);
            assertTrue(task.evaluate(cond, ctx).isPresent());
            var state = rc.namespaceKv(ctx.getNamespace()).getValue(Trigger.watermarkKey(ctx.getFlowId(), id)).orElseThrow();
            var watermark = JacksonMapper.ofJson().readValue((byte[]) state.value(), Trigger.Watermark.class);
            assertEquals(new Trigger.Watermark("number", "10"), watermark);
            // A repeated non-empty response must not re-fire even if the service returns stale rows.
            assertFalse(task.evaluate(cond, ctx).isPresent());
            stubResult();
            assertFalse(task.evaluate(cond, ctx).isPresent());
        }
        server.verify(4, postRequestedFor(urlPathEqualTo("/projects/analytics/instances"))
            .withRequestBody(containing("id &gt; &apos;10&apos;")));
    }

    @Test
    void cancellationDuringSubmissionStopsReturnedInstanceOnce() throws Exception {
        var task = query(FetchType.FETCH).build();
        var instance = mock(Instance.class);
        when(instance.getId()).thenReturn("cancelled");
        try (var sql = mockStatic(SQLTask.class)) {
            sql.when(() -> SQLTask.run(any(Odps.class), anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    task.kill();
                    task.kill();
                    return instance;
                });
            assertThrows(CancellationException.class, () -> task.run(runContextFactory.of(Map.of())));
            verify(instance, times(1)).stop();
        }
    }

    @Test
    void corruptStateFailsBeforeCallingService() throws Exception {
        var id = "corrupt-" + UUID.randomUUID();
        var task = trigger(FetchType.FETCH, id).build();
        var ctx = TriggerContext.builder().tenantId("main").namespace("company.team").flowId("state_test").triggerId(id).build();
        var rc = runContextFactory.of(Map.of("flow", Map.of("namespace", ctx.getNamespace(), "id", ctx.getFlowId(), "tenantId", "main")));
        runContextFactory.initializer().forScheduler((DefaultRunContext) rc, ctx, task);
        var cond = ConditionContext.builder().runContext(rc)
            .flow(Flow.builder().id(ctx.getFlowId()).namespace(ctx.getNamespace()).revision(1).build()).build();
        for (var state : List.of("invalid JSON", "null", "{}", "{\"type\":\"number\",\"value\":\"bad\"}",
            "{\"type\":\"instant\",\"value\":\"bad\"}", "{\"type\":\"unknown\",\"value\":\"10\"}")) {
            rc.namespaceKv(ctx.getNamespace()).put(Trigger.watermarkKey(ctx.getFlowId(), id),
                new KVValueAndMetadata(new KVMetadata("corrupted", (Duration) null), state.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThrows(Exception.class, () -> task.evaluate(cond, ctx), state);
        }
        assertEquals(0, server.getAllServeEvents().size());
    }

    @Test
    void watermarkTypesAndKeysAreStable() {
        assertEquals("O\\'Reilly\\\\logs\\n", Trigger.escapeWatermark("O'Reilly\\logs\n"));
        assertTrue(Trigger.Watermark.of(10L).compareTo(Trigger.Watermark.of(9L)) > 0);
        assertTrue(Trigger.Watermark.of("2026-01-01T00:00:00.1Z").compareTo(Trigger.Watermark.of("2026-01-01T00:00:00Z")) > 0);
        assertNotEquals(Trigger.watermarkKey("a_b", "c"), Trigger.watermarkKey("a", "b_c"));
        assertThrows(IllegalArgumentException.class, () -> Trigger.Watermark.of(null));
    }
}
