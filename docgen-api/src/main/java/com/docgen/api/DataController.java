package com.docgen.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings → "Apagar todos os meus dados" (the UI asks for confirmation first). */
@RestController
@RequestMapping("/api/v1/data")
class DataController {
    private final DataErasureService erasure;

    DataController(DataErasureService erasure) {
        this.erasure = erasure;
    }

    @DeleteMapping
    ResponseEntity<Void> eraseAll() {
        erasure.eraseAll(CurrentUser.username());
        return ResponseEntity.noContent().build();
    }
}
