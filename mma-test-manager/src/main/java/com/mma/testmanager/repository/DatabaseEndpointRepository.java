package com.mma.testmanager.repository;

import com.mma.testmanager.entity.DatabaseEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DatabaseEndpointRepository extends JpaRepository<DatabaseEndpoint, Long> {
}
