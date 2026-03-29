package com.mma.testmanager.entity;

import lombok.Data;

@Data
public class Dependency {
    private String type;
    private String owner;
    private String name;
    private String referencedType;
    private String referencedOwner;
    private String referencedName;
    private String backupScript;
}
