-- =============================================================================
-- 30 — PDB FREEPDB1: usuário/schema PROJUDI
--
-- Parâmetro posicional:
--   1 = senha do usuário PROJUDI
--
-- Este é o schema de origem: no ambiente local a PROJUDI.LOG é uma tabela
-- comum (em produção o LogPs escreve nela), e a PROJUDI.PROC é a tabela que o
-- Debezium vai capturar do redo log.
-- =============================================================================

WHENEVER SQLERROR EXIT SQL.SQLCODE
WHENEVER OSERROR  EXIT FAILURE
SET ECHO ON
SET VERIFY OFF

DEFINE projudi_pwd = &1

CREATE USER PROJUDI IDENTIFIED BY "&projudi_pwd"
    DEFAULT TABLESPACE projudi_tbs
    TEMPORARY TABLESPACE temp
    QUOTA UNLIMITED ON projudi_tbs;

GRANT CREATE SESSION      TO PROJUDI;
GRANT CREATE TABLE        TO PROJUDI;
GRANT CREATE SEQUENCE     TO PROJUDI;
GRANT CREATE VIEW         TO PROJUDI;
GRANT CREATE PROCEDURE    TO PROJUDI;

EXIT SUCCESS;
