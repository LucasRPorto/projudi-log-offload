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
-- -----------------------------------------------------------------------------
-- ATENÇÃO AO CAMINHO DO DATAFILE
-- -----------------------------------------------------------------------------
-- NUNCA use nome de arquivo relativo aqui. `DATAFILE 'nome.dbf'` faz o Oracle
-- resolver relativo a $ORACLE_HOME/dbs, que fica na CAMADA GRAVÁVEL DA IMAGEM,
-- fora do volume nomeado oracle-data. O ambiente funciona normalmente até o
-- container ser RECRIADO (`docker compose down` + `up`, atualização de imagem,
-- reinício do Docker Desktop): a camada gravável some, os datafiles somem
-- junto, e o banco entra em loop de restart com
--
--   ORA-01157: cannot identify/lock data file NN
--   ORA-01110: data file NN: '.../dbhomeFree/dbs/logminer_tbs_root.dbf'
--
-- sem forma prática de recuperação. Aconteceu de verdade em 2026-07-21; ver
-- docs/decisoes.md, seção 18.
--
-- O diretório correto é derivado em tempo de execução do datafile da SYSTEM,
-- que por construção está dentro do volume. Não é hardcoded porque o layout
-- muda entre versões da imagem (o caminho já mudou de 23ai para 26ai).
-- =============================================================================

WHENEVER SQLERROR EXIT SQL.SQLCODE
WHENEVER OSERROR  EXIT FAILURE
SET ECHO ON
SET VERIFY OFF

-- Descobre o diretório onde este container guarda seus datafiles
SET TERMOUT OFF
COLUMN dbf_dir NEW_VALUE dbf_dir NOPRINT
SELECT SUBSTR(file_name, 1, INSTR(file_name, '/', -1)) AS dbf_dir
FROM   dba_data_files
WHERE  tablespace_name = 'SYSTEM'
  AND  ROWNUM = 1;
SET TERMOUT ON

PROMPT Diretorio de datafiles do PDB: &dbf_dir

-- Falha alto se o caminho não estiver sob o volume: melhor abortar o init do
-- que criar um banco que morre no primeiro `docker compose down`.
BEGIN
    IF '&dbf_dir' NOT LIKE '/opt/oracle/oradata/%' THEN
        RAISE_APPLICATION_ERROR(-20010,
            'Datafile fora do volume oracle-data: &dbf_dir');
    END IF;
END;
/

CREATE TABLESPACE logminer_tbs
    DATAFILE '&dbf_dir.logminer_tbs.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

CREATE TABLESPACE projudi_tbs
    DATAFILE '&dbf_dir.projudi_tbs.dbf'
    SIZE 100M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

EXIT SUCCESS;
