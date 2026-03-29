package com.mma.testmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "database_endpoints")
@Data
public class DatabaseEndpoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String endpointType; // SOURCE or TARGET
    private String vendorName;
    private String vendorEngine;
    private String vendorEngineVersion;
    private String vendorEdition;
    private String connectionData;
    private String dbEngine; // Standardized AWS engine names: oracle, postgres, sqlserver, mysql
    private LocalDateTime createdAt;
}
