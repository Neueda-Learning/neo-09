package com.neobank.module.support.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "case_config")
public class CaseConfig {

    @Id
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories_json", nullable = false, columnDefinition = "json")
    private String categoriesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "priority_map_json", nullable = false, columnDefinition = "json")
    private String priorityMapJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sla_hours_json", nullable = false, columnDefinition = "json")
    private String slaHoursJson;

    protected CaseConfig() {
        // JPA
    }

    public Integer getVersion() {
        return version;
    }

    public String getCategoriesJson() {
        return categoriesJson;
    }

    public String getPriorityMapJson() {
        return priorityMapJson;
    }

    public String getSlaHoursJson() {
        return slaHoursJson;
    }
}
