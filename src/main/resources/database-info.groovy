import io.dbmaster.api.groovy.DbmTools

def TABLE_QUERY = """
    WITH ps as (
        SELECT
            ps.object_id,
            SUM ( CASE WHEN (ps.index_id < 2) THEN row_count    ELSE 0 END ) AS [rows],
            SUM (ps.reserved_page_count) AS reserved,
            SUM (CASE   WHEN (ps.index_id < 2) THEN (ps.in_row_data_page_count + ps.lob_used_page_count + ps.row_overflow_used_page_count)
                        ELSE (ps.lob_used_page_count + ps.row_overflow_used_page_count) END
                 ) AS data,
            SUM (ps.used_page_count) AS used
        FROM sys.dm_db_partition_stats ps
        GROUP BY ps.object_id
    ),
    object_compression as (
        select distinct p.object_id, 
            substring((
                    select ','+p2.data_compression_desc  AS [text()]
                    from (select distinct object_id,data_compression_desc from sys.partitions where index_id<2) p2
                    Where p.object_id = p2.object_id
                    ORDER BY p2.object_id
                    For XML PATH ('')
                ), 2, 1000) compression_desc
        from sys.partitions p
    )
    SELECT
            s.name AS SchemaName,
            o.name AS TableName,
            ps.rows as Rows,
            o.type_desc as Type,
            (ps.reserved )* 8.0 / 1024 AS ReservedMb,
            ps.data * 8.0 / 1024 AS DataSizeMb,
            (CASE WHEN (ps.used ) > ps.data THEN (ps.used ) - ps.data ELSE 0 END) * 8.0 / 1024 AS IndexSizeMb,
            (CASE WHEN (ps.reserved ) > ps.used THEN (ps.reserved ) - ps.used ELSE 0 END) * 8.0 / 1024 AS UnusedMb,
            oc.compression_desc as TableCompression
    FROM ps
    INNER JOIN sys.all_objects o  ON ( ps.object_id = o.object_id )
    INNER JOIN sys.schemas s ON (o.schema_id = s.schema_id)
    LEFT JOIN object_compression AS oc ON oc.OBJECT_ID = o.OBJECT_ID
    WHERE o.type <> N'S' AND  o.type <> N'IT' -- S SYSTEM_TABLE, IT- INTERNAL_TABLE
    ORDER BY ps.data DESC"""
    
def INDEX_QUERY = """
WITH database_indexes AS (
    SELECT
        -- i.index_id AS IndexID,
        SCHEMA_NAME(o.schema_id) as SchemaName,
        OBJECT_NAME(i.object_id) AS TableName,
        i.name AS IndexName,
        max(i.type_desc) as IndexType,
        8 * SUM(a.used_pages) / 1024 AS IndexSizeMb,
        p.partition_number as PartitionNumber, 
        p.data_compression_desc as CompressionType
    FROM sys.indexes AS i
    JOIN sys.partitions AS p ON p.OBJECT_ID = i.OBJECT_ID AND p.index_id = i.index_id
    JOIN sys.allocation_units AS a ON a.container_id = p.partition_id
    JOIN sys.objects AS o on i.object_id = o.object_id
    -- where i.type<>1 and i.type<>0 and i.is_primary_key=0 -- 0 - heap; 1 - clustered 
    WHERE o.is_ms_shipped = 0
    GROUP BY o.schema_id, 
             i.OBJECT_ID,
             i.index_id,
             i.name, 
             p.partition_number, 
             p.data_compression_desc
)
SELECT * 
FROM database_indexes 
ORDER BY IndexSizeMb DESC"""
    

def tools = new DbmTools(dbm, logger, getBinding().out)
def connection = tools.getConnection(p_server, p_database)

println "<h2>Tables</h2>"
tools.print(connection, TABLE_QUERY)

println "<h2>Indexes</h2>"
tools.print(connection, INDEX_QUERY)