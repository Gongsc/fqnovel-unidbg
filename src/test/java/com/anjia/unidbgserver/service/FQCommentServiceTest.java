package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.dto.DeviceInfo;
import com.anjia.unidbgserver.dto.FQCommentReplyListRequest;
import com.anjia.unidbgserver.dto.FQVariable;
import com.anjia.unidbgserver.utils.FQApiUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FQCommentServiceTest {
    @Mock private FQEncryptServiceWorker fqEncryptServiceWorker;
    @Mock private FQApiUtils fqApiUtils;
    @Mock private DevicePoolService devicePoolService;
    @Mock private RestTemplate restTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private FQCommentService service;

    @Test
    void retryBuildsQueryAndHeadersFromTheSameDevice() throws Exception {
        DeviceInfo first = DeviceInfo.builder().installId("install-a").deviceId("a").build();
        DeviceInfo second = DeviceInfo.builder().installId("install-b").deviceId("b").build();
        when(devicePoolService.getTargetPoolSize()).thenReturn(1);
        when(devicePoolService.nextDevice()).thenReturn(first, second);
        when(fqApiUtils.getBaseUrl()).thenReturn("https://example.test");
        when(fqApiUtils.buildCommonApiParams(any())).thenAnswer(call ->
                Map.of("iid", call.<FQVariable>getArgument(0).getInstallId()));
        when(fqApiUtils.buildUrlWithParams(anyString(), anyMap())).thenAnswer(call ->
                call.<String>getArgument(0) + "?iid=" + call.<Map<String, String>>getArgument(1).get("iid"));
        when(fqApiUtils.buildCommonHeaders(any())).thenAnswer(call ->
                new HashMap<>(Map.of("Cookie", "install_id=" + call.<DeviceInfo>getArgument(0).getInstallId())));
        when(fqEncryptServiceWorker.generateSignatureHeaders(anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenAnswer(call -> {
                    String url = call.getArgument(0);
                    HttpEntity<?> request = call.getArgument(2);
                    String installId = url.substring(url.indexOf("?iid=") + 5);
                    assertEquals("install_id=" + installId, request.getHeaders().getFirst("Cookie"));
                    return ResponseEntity.ok(installId.equals("install-a") ? new byte[0]
                            : "{\"code\":0}".getBytes(StandardCharsets.UTF_8));
                });

        FQCommentReplyListRequest request = new FQCommentReplyListRequest();
        request.setCommentId("123");
        assertEquals(0, service.getReplyList(request).get(5, TimeUnit.SECONDS).getCode());
        verify(devicePoolService, times(2)).nextDevice();
        verify(devicePoolService).removeAndReplenish(first, "段评回复列表 empty response");
    }
}
