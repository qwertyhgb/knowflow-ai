package io.github.qwertyhgb.knowflow.knowledge.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 文档状态，对应表 {@code document.status}。
 *
 * <p>字符串枚举直接落库（参照 {@link KnowledgeBaseAccessMode} 与 V4 invitation.status 先例），
 * 数据库列 {@code status} 为 VARCHAR(16)，存可读英文单词，日志与数据库直接查询时直观可读。</p>
 *
 * <p><strong>状态机设计：</strong></p>
 * <pre>
 *   UPLOADED  →  PARSING  →  PARSED  →  INDEXING  →  READY
 *        ↓          ↓           ↓          ↓
 *     FAILED     FAILED      FAILED      FAILED
 * </pre>
 * <ul>
 *   <li>{@link #UPLOADED}：文件已上传到本地磁盘，记录已写入数据库，为状态机起点（本步只用到此状态）；</li>
 *   <li>{@link #PARSING}：正在解析文档内容（后续步骤）；</li>
 *   <li>{@link #PARSED}：文档内容已解析完成（后续步骤）；</li>
 *   <li>{@link #INDEXING}：已解析内容正在建立索引（后续步骤）；</li>
 *   <li>{@link #READY}：解析与索引全部完成，文档可被检索（后续步骤）；</li>
 *   <li>{@link #FAILED}：上述任一步骤失败后的终态，不可恢复，需重新上传（后续步骤）。</li>
 * </ul>
 */
@Getter
public enum DocumentStatus {

    /** 文件已上传到本地磁盘，记录已写入数据库（状态机起点）。 */
    UPLOADED("UPLOADED"),

    /** 正在解析文档内容（预留，后续步骤）。 */
    PARSING("PARSING"),

    /** 文档内容已解析完成（预留，后续步骤）。 */
    PARSED("PARSED"),

    /** 已解析内容正在建立索引（预留，后续步骤）。 */
    INDEXING("INDEXING"),

    /** 解析与索引全部完成，文档可被检索（预留，后续步骤）。 */
    READY("READY"),

    /** 上述任一步骤失败后的终态，不可恢复，需重新上传（预留，后续步骤）。 */
    FAILED("FAILED");

    @EnumValue
    private final String value;

    DocumentStatus(String value) {
        this.value = value;
    }
}