package com.questionos.backend.api;

import com.questionos.backend.api.dto.BackgroundDtos;
import com.questionos.backend.service.BackgroundTextExtractService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/background")
@CrossOrigin(
        originPatterns = {"https://*.up.railway.app", "http://localhost:*", "http://127.0.0.1:*"},
        allowedHeaders = {"Authorization", "Content-Type", "X-API-Version", "Idempotency-Key", "Last-Event-ID", "Accept", "Origin"},
        methods = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS},
        allowCredentials = "false"
)
public class BackgroundExtractController {
    private final BackgroundTextExtractService extractService;

    public BackgroundExtractController(BackgroundTextExtractService extractService) {
        this.extractService = extractService;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Object>> extract(@RequestPart("file") FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .publishOn(Schedulers.boundedElastic())
                .map(buffer -> {
                    try {
                        int n = buffer.readableByteCount();
                        byte[] bytes = new byte[n];
                        buffer.read(bytes);
                        BackgroundDtos.ExtractResponse r = extractService.extract(bytes, filePart.filename());
                        return ResponseEntity.<Object>ok(r);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body((Object) new BackgroundDtos.ErrorBody(e.getMessage()))))
                .onErrorResume(Exception.class, e -> Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body((Object) new BackgroundDtos.ErrorBody("无法解析该文档，请尝试另存为 .docx 或导出为 .txt"))));
    }
}
