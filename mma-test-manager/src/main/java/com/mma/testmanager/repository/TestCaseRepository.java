package com.mma.testmanager.repository;

import com.mma.testmanager.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {
    List<TestCase> findByObjectId(Long objectId);
    List<TestCase> findByObjectIdOrderByTestNumberAsc(Long objectId);
}
