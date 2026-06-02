package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.TermCategory;
import com.ai.moderation.domain.ViolationTerm;
import com.ai.moderation.dto.TermCategoryRequest;
import com.ai.moderation.dto.TermCategoryResponse;
import com.ai.moderation.dto.ViolationTermRequest;
import com.ai.moderation.dto.ViolationTermResponse;
import com.ai.moderation.repository.TermCategoryRepository;
import com.ai.moderation.repository.ViolationTermRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
public class TermService {
    private final ViolationTermRepository termRepository;
    private final TermCategoryRepository categoryRepository;

    public TermService(ViolationTermRepository termRepository, TermCategoryRepository categoryRepository) {
        this.termRepository = termRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<ViolationTermResponse> listTerms(String keyword) {
        List<ViolationTerm> terms = StringUtils.hasText(keyword)
                ? termRepository.findByTermContainingIgnoreCaseOrCategoryContainingIgnoreCaseOrderByUpdatedAtDesc(keyword, keyword)
                : termRepository.findAll();
        return terms.stream().map(ViolationTermResponse::from).toList();
    }

    @Transactional
    public ViolationTermResponse createTerm(ViolationTermRequest request) {
        if (termRepository.existsByTermIgnoreCaseAndMatchType(request.term().trim(), request.matchType())) {
            throw new ApiException(HttpStatus.CONFLICT, "相同词条和匹配方式已存在");
        }
        ViolationTerm term = new ViolationTerm();
        apply(term, request);
        return ViolationTermResponse.from(termRepository.save(term));
    }

    @Transactional
    public ViolationTermResponse updateTerm(Long id, ViolationTermRequest request) {
        ViolationTerm term = termRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "违规词不存在"));
        apply(term, request);
        term.setUpdatedAt(Instant.now());
        return ViolationTermResponse.from(termRepository.save(term));
    }

    @Transactional
    public void deleteTerm(Long id) {
        if (!termRepository.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "违规词不存在");
        }
        termRepository.deleteById(id);
    }

    @Transactional
    public com.ai.moderation.dto.TermImportResponse importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请上传 CSV 文件");
        }
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            int imported = 0;
            int skipped = 0;
            for (String line : content.split("\\R")) {
                if (line.isBlank() || line.startsWith("term,")) {
                    continue;
                }
                String[] columns = line.split(",", -1);
                if (columns.length < 1 || columns[0].isBlank()) {
                    skipped++;
                    continue;
                }
                com.ai.moderation.domain.MatchType matchType = columns.length > 3 && StringUtils.hasText(columns[3])
                        ? com.ai.moderation.domain.MatchType.valueOf(columns[3].trim().toUpperCase())
                        : com.ai.moderation.domain.MatchType.EXACT;
                if (termRepository.existsByTermIgnoreCaseAndMatchType(columns[0].trim(), matchType)) {
                    skipped++;
                    continue;
                }
                ViolationTerm term = new ViolationTerm();
                term.setTerm(columns[0].trim());
                term.setCategory(columns.length > 1 ? blankToNull(columns[1]) : null);
                term.setSeverity(columns.length > 2 && StringUtils.hasText(columns[2])
                        ? com.ai.moderation.domain.Severity.valueOf(columns[2].trim().toUpperCase())
                        : com.ai.moderation.domain.Severity.MEDIUM);
                term.setMatchType(matchType);
                term.setEnabled(true);
                term.setVariants(columns.length > 4 ? blankToNull(columns[4]) : null);
                termRepository.save(term);
                imported++;
            }
            return new com.ai.moderation.dto.TermImportResponse(imported, skipped);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 中存在非法枚举值: " + ex.getMessage());
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 CSV 失败: " + ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<TermCategoryResponse> listCategories() {
        return categoryRepository.findAll().stream().map(TermCategoryResponse::from).toList();
    }

    @Transactional
    public TermCategoryResponse createCategory(TermCategoryRequest request) {
        categoryRepository.findByName(request.name()).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "分类名称已存在");
        });
        TermCategory category = new TermCategory();
        category.setName(request.name());
        category.setDescription(request.description());
        return TermCategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public TermCategoryResponse updateCategory(Long id, TermCategoryRequest request) {
        TermCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "分类不存在"));
        category.setName(request.name());
        category.setDescription(request.description());
        return TermCategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public void deleteCategory(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "分类不存在");
        }
        categoryRepository.deleteById(id);
    }

    private void apply(ViolationTerm term, ViolationTermRequest request) {
        term.setTerm(request.term().trim());
        term.setCategory(request.category());
        term.setSeverity(request.severity());
        term.setMatchType(request.matchType());
        term.setEnabled(request.enabled());
        term.setVariants(request.variants());
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
