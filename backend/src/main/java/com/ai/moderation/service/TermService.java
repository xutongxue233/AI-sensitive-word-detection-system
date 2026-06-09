package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TermCategory;
import com.ai.moderation.domain.ViolationTerm;
import com.ai.moderation.dto.BatchItemResponse;
import com.ai.moderation.dto.BatchOperationResponse;
import com.ai.moderation.dto.GeneratedTermResponse;
import com.ai.moderation.dto.TermBatchUpdateRequest;
import com.ai.moderation.dto.TermCategoryRequest;
import com.ai.moderation.dto.TermCategoryResponse;
import com.ai.moderation.dto.TermGenerationRequest;
import com.ai.moderation.dto.TermImportResponse;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

/**
 * 违规词与分类的管理服务:违规词/分类的 CRUD 与 CSV 批量导入。
 *
 * <p>维护的词库是规则召回的数据源,供 {@link RuleMatchingService} 在检测时匹配候选命中。
 * 词条以 {@code term + matchType} 组合唯一,导入与新增都按此约束去重。
 */
@Service
public class TermService {
    private final ViolationTermRepository termRepository;
    private final TermCategoryRepository categoryRepository;
    private final SettingsService settingsService;
    private final AiModerationClient aiModerationClient;

    public TermService(
            ViolationTermRepository termRepository,
            TermCategoryRepository categoryRepository,
            SettingsService settingsService,
            AiModerationClient aiModerationClient
    ) {
        this.termRepository = termRepository;
        this.categoryRepository = categoryRepository;
        this.settingsService = settingsService;
        this.aiModerationClient = aiModerationClient;
    }

    @Transactional(readOnly = true)
    public List<ViolationTermResponse> listTerms(String keyword) {
        List<ViolationTerm> terms = StringUtils.hasText(keyword)
                ? termRepository.findByTermContainingIgnoreCaseOrCategoryContainingIgnoreCaseOrderByUpdatedAtDesc(keyword, keyword)
                : termRepository.findAll();
        return terms.stream().map(ViolationTermResponse::from).toList();
    }

    /** 新增违规词。唯一性约束为 {@code term + matchType} 组合:同词同匹配方式已存在则抛 409 冲突。 */
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
    public BatchOperationResponse batchUpdateTerms(TermBatchUpdateRequest request) {
        List<BatchItemResponse> items = new ArrayList<>();
        for (Long id : request.ids()) {
            try {
                ViolationTerm term = termRepository.findById(id)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "违规词不存在"));
                if (request.enabled() != null) {
                    term.setEnabled(request.enabled());
                }
                if (request.category() != null) {
                    term.setCategory(blankToNull(request.category()));
                }
                if (request.severity() != null) {
                    term.setSeverity(request.severity());
                }
                if (request.matchType() != null) {
                    term.setMatchType(request.matchType());
                }
                term.setUpdatedAt(Instant.now());
                termRepository.save(term);
                items.add(BatchItemResponse.ok(id, "已更新"));
            } catch (Exception ex) {
                items.add(BatchItemResponse.failed(id, readableMessage(ex)));
            }
        }
        return BatchOperationResponse.from(items);
    }

    @Transactional
    public BatchOperationResponse batchDeleteTerms(List<Long> ids) {
        List<BatchItemResponse> items = new ArrayList<>();
        for (Long id : ids) {
            try {
                deleteTerm(id);
                items.add(BatchItemResponse.ok(id, "已删除"));
            } catch (Exception ex) {
                items.add(BatchItemResponse.failed(id, readableMessage(ex)));
            }
        }
        return BatchOperationResponse.from(items);
    }

    /**
     * CSV 批量导入违规词。列序约定(0 基):
     * <ol start="0">
     *   <li>term:词条,必填,空则跳过并计入 skipped;
     *   <li>category:分类,可空;
     *   <li>severity:严重度,缺省 {@link Severity#MEDIUM};
     *   <li>matchType:匹配方式,缺省 {@link MatchType#EXACT};
     *   <li>variants:变体,可空。
     * </ol>
     * 首行表头({@code term,} 开头)与已存在的重复词条({@code term+matchType})跳过并计入 skipped;
     * 非法枚举值整体抛 400。
     *
     * @param file 上传的 CSV 文件,为空时抛 400
     * @return 导入统计:成功 imported 条、跳过 skipped 条
     */
    @Transactional
    public TermImportResponse importCsv(MultipartFile file) {
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
                MatchType matchType = columns.length > 3 && StringUtils.hasText(columns[3])
                        ? MatchType.valueOf(columns[3].trim().toUpperCase())
                        : MatchType.EXACT;
                if (termRepository.existsByTermIgnoreCaseAndMatchType(columns[0].trim(), matchType)) {
                    skipped++;
                    continue;
                }
                ViolationTerm term = new ViolationTerm();
                term.setTerm(columns[0].trim());
                term.setCategory(columns.length > 1 ? blankToNull(columns[1]) : null);
                term.setSeverity(columns.length > 2 && StringUtils.hasText(columns[2])
                        ? Severity.valueOf(columns[2].trim().toUpperCase())
                        : Severity.MEDIUM);
                term.setMatchType(matchType);
                term.setEnabled(true);
                term.setVariants(columns.length > 4 ? blankToNull(columns[4]) : null);
                termRepository.save(term);
                imported++;
            }
            return new TermImportResponse(imported, skipped);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV 中存在非法枚举值: " + ex.getMessage());
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 CSV 失败: " + ex.getMessage());
        }
    }

    /**
     * 根据自然语言需求调用当前 AI 配置生成候选词条。结果只返回给前端预览,不直接入库。
     */
    public List<GeneratedTermResponse> generateTerms(TermGenerationRequest request) {
        if (!settingsService.currentAi().enabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI 未启用，请先在系统设置中开启 AI 复核并配置模型");
        }
        int count = request.count() == null ? 12 : Math.max(1, Math.min(50, request.count()));
        List<ViolationTerm> existingTerms = termRepository.findAll().stream().limit(120).toList();
        List<GeneratedTermResponse> generated = aiModerationClient.generateTerms(
                request.prompt().trim(),
                blankToNull(request.category()),
                count,
                existingTerms
        );
        Set<String> seen = new HashSet<>();
        return generated.stream()
                .filter(item -> StringUtils.hasText(item.term()))
                .filter(item -> !termRepository.existsByTermIgnoreCaseAndMatchType(item.term().trim(), item.matchType()))
                .filter(item -> seen.add(item.term().trim().toLowerCase() + "|" + item.matchType()))
                .limit(count)
                .toList();
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

    private String readableMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
