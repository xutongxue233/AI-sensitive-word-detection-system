package com.ai.moderation.controller;

import com.ai.moderation.dto.BatchIdsRequest;
import com.ai.moderation.dto.BatchOperationResponse;
import com.ai.moderation.dto.GeneratedTermResponse;
import com.ai.moderation.dto.TermBatchUpdateRequest;
import com.ai.moderation.dto.TermCategoryRequest;
import com.ai.moderation.dto.TermCategoryResponse;
import com.ai.moderation.dto.TermGenerationRequest;
import com.ai.moderation.dto.TermImportResponse;
import com.ai.moderation.dto.ViolationTermRequest;
import com.ai.moderation.dto.ViolationTermResponse;
import com.ai.moderation.service.TermService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 敏感词词库与词分类的管理 REST 入口,统一前缀 {@code /api/v1}。
 *
 * <p>聚合 {@link TermService},提供违规词({@code /terms})与词分类({@code /term-categories})
 * 的增删改查,以及 CSV 批量导入。这些词库是检测管线召回阶段
 * {@code RuleMatchingService} 规则匹配的数据基础,在管线运行前由人工维护。
 */
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

    @PatchMapping("/terms/batch")
    public BatchOperationResponse batchUpdateTerms(@Valid @RequestBody TermBatchUpdateRequest request) {
        return termService.batchUpdateTerms(request);
    }

    @DeleteMapping("/terms/batch")
    public BatchOperationResponse batchDeleteTerms(@Valid @RequestBody BatchIdsRequest request) {
        return termService.batchDeleteTerms(request.ids());
    }

    @PostMapping("/terms/import")
    public TermImportResponse importTerms(@RequestPart("file") MultipartFile file) {
        return termService.importCsv(file);
    }

    @PostMapping("/terms/ai-generate")
    public List<GeneratedTermResponse> generateTerms(@Valid @RequestBody TermGenerationRequest request) {
        return termService.generateTerms(request);
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
