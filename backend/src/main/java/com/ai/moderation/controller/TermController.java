package com.ai.moderation.controller;

import com.ai.moderation.dto.*;
import com.ai.moderation.service.TermService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class TermController {
    private final TermService termService;

    public TermController(TermService termService) {
        this.termService = termService;
    }

    @GetMapping("/terms")
    public List<ViolationTermResponse> listTerms(@RequestParam(required = false) String keyword) {
        return termService.listTerms(keyword);
    }

    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public ViolationTermResponse createTerm(@Valid @RequestBody ViolationTermRequest request) {
        return termService.createTerm(request);
    }

    @PatchMapping("/terms/{id}")
    public ViolationTermResponse updateTerm(@PathVariable Long id, @Valid @RequestBody ViolationTermRequest request) {
        return termService.updateTerm(id, request);
    }

    @DeleteMapping("/terms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTerm(@PathVariable Long id) {
        termService.deleteTerm(id);
    }

    @PostMapping("/terms/import")
    public TermImportResponse importTerms(@RequestPart("file") MultipartFile file) {
        return termService.importCsv(file);
    }

    @GetMapping("/term-categories")
    public List<TermCategoryResponse> listCategories() {
        return termService.listCategories();
    }

    @PostMapping("/term-categories")
    @ResponseStatus(HttpStatus.CREATED)
    public TermCategoryResponse createCategory(@Valid @RequestBody TermCategoryRequest request) {
        return termService.createCategory(request);
    }

    @PatchMapping("/term-categories/{id}")
    public TermCategoryResponse updateCategory(@PathVariable Long id, @Valid @RequestBody TermCategoryRequest request) {
        return termService.updateCategory(id, request);
    }

    @DeleteMapping("/term-categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable Long id) {
        termService.deleteCategory(id);
    }
}
