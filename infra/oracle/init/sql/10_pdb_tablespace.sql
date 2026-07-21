-- =============================================================================
-- 10 — Tablespaces dentro do PDB FREEPDB1
--
-- LOGMINER_TBS : default tablespace do usuário comum do Debezium. Precisa
--                existir também no CDB$ROOT (ver 20_cdb_debezium_user.sql):
--                um usuário criado com CONTAINER=ALL só pode ter como default
--                uma tablespace presente em TODOS os containers.
-- PROJUDI_TBS  : dados do schema de origem, separados de USERS para deixar
--                claro no relatório o que é dado da aplicação.
--
-- Executado com uma connect string explícita para FREEPDB1 pelo orquestrador.
-- =============================================================================

WHENEVER SQLERROR EXIT SQL.SQLCODE
WHENEVER OSERROR  EXIT FAILURE
SET ECHO ON
SET VERIFY OFF

CREATE TABLESPACE logminer_tbs
    DATAFILE 'logminer_tbs.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

CREATE TABLESPACE projudi_tbs
    DATAFILE 'projudi_tbs.dbf'
    SIZE 100M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

EXIT SUCCESS;
