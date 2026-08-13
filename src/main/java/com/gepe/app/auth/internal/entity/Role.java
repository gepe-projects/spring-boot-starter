package com.gepe.app.auth.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "roles", schema = "auth")
@Getter
public class Role {

    @Id
    @Column(nullable = false, length = 20)
    private String name;

    @Column(nullable = false)
    private String description;

    protected Role() {}

    public Role(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
