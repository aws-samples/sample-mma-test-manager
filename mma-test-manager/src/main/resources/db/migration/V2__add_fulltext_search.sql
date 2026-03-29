-- V2: Add fulltext search with GIN index

-- Add generated tsvector column
ALTER TABLE database_objects 
ADD COLUMN IF NOT EXISTS search_vector tsvector 
GENERATED ALWAYS AS (
  to_tsvector('simple', 
    COALESCE(source_schema_name, '') || ' ' || 
    COALESCE(source_object_type, '') || ' ' || 
    COALESCE(source_package_name, '') || ' ' || 
    COALESCE(source_object_name, '') || ' ' || 
    COALESCE(target_schema_name, '') || ' ' || 
    COALESCE(target_object_name, '')
  )
) STORED;

-- Create GIN index on the tsvector column
CREATE INDEX IF NOT EXISTS idx_database_objects_search_vector 
ON database_objects USING GIN (search_vector);
