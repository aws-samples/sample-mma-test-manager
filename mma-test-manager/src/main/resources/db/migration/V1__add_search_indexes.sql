-- V1: Add functional indexes for case-insensitive search performance
-- This runs after Hibernate creates the tables

-- Create functional indexes for case-insensitive search on object_name and package_name
CREATE INDEX IF NOT EXISTS idx_database_objects_object_name_upper 
ON database_objects (UPPER(object_name));

CREATE INDEX IF NOT EXISTS idx_database_objects_package_name_upper 
ON database_objects (UPPER(package_name));

-- Composite index for common query pattern (project_id + upper object_name)
CREATE INDEX IF NOT EXISTS idx_database_objects_project_object_upper 
ON database_objects (project_id, UPPER(object_name));

-- Composite index for common query pattern (project_id + upper package_name)
CREATE INDEX IF NOT EXISTS idx_database_objects_project_package_upper 
ON database_objects (project_id, UPPER(package_name));
