-- =============================================================================
-- 20 — CDB$ROOT: usuário comum do Debezium, privilégios de LogMiner e
--      supplemental logging em nível de banco.
--
-- Parâmetros (posicionais, passados pelo orquestrador):
--   1 = nome do usuário comum (precisa começar com C## num CDB)
--   2 = senha
--
-- Referência dos privilégios: documentação do Debezium Oracle Connector,
-- seção "Preparing the database" / "Creating users for the connector".
-- =============================================================================

WHENEVER SQLERROR EXIT SQL.SQLCODE
WHENEVER OSERROR  EXIT FAILURE
SET ECHO ON
SET VERIFY OFF
SET SERVEROUTPUT ON SIZE UNLIMITED

DEFINE dbz_user = &1
DEFINE dbz_pwd  = &2

-- -----------------------------------------------------------------------------
-- Tablespace do LogMiner no root (a contraparte no PDB foi criada no passo 10)
--
-- O caminho do datafile é derivado em tempo de execução, NUNCA relativo — um
-- nome relativo cai em $ORACLE_HOME/dbs, fora do volume, e o banco deixa de
-- abrir assim que o container for recriado. Ver o cabeçalho de
-- 10_pdb_tablespace.sql e docs/decisoes.md, seção 18.
-- -----------------------------------------------------------------------------
SET TERMOUT OFF
COLUMN dbf_dir NEW_VALUE dbf_dir NOPRINT
SELECT SUBSTR(file_name, 1, INSTR(file_name, '/', -1)) AS dbf_dir
FROM   dba_data_files
WHERE  tablespace_name = 'SYSTEM'
  AND  ROWNUM = 1;
SET TERMOUT ON

PROMPT Diretorio de datafiles do CDB$ROOT: &dbf_dir

BEGIN
    IF '&dbf_dir' NOT LIKE '/opt/oracle/oradata/%' THEN
        RAISE_APPLICATION_ERROR(-20010,
            'Datafile fora do volume oracle-data: &dbf_dir');
    END IF;
END;
/

CREATE TABLESPACE logminer_tbs
    DATAFILE '&dbf_dir.logminer_tbs_root.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

-- -----------------------------------------------------------------------------
-- Supplemental logging em nível de banco.
--
-- Sem isso o redo log guarda apenas o ROWID e as colunas alteradas, e o
-- LogMiner não consegue reconstruir a linha: o Debezium emitiria eventos com
-- quase tudo nulo. Num CDB este comando é executado no CDB$ROOT e vale para
-- todos os PDBs.
-- -----------------------------------------------------------------------------
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;

-- -----------------------------------------------------------------------------
-- Usuário comum do conector
-- -----------------------------------------------------------------------------
CREATE USER &dbz_user IDENTIFIED BY "&dbz_pwd"
    DEFAULT TABLESPACE logminer_tbs
    QUOTA UNLIMITED ON logminer_tbs
    CONTAINER = ALL;

GRANT CREATE SESSION            TO &dbz_user CONTAINER = ALL;
GRANT SET CONTAINER             TO &dbz_user CONTAINER = ALL;
GRANT LOGMINING                 TO &dbz_user CONTAINER = ALL;
GRANT SELECT ANY TABLE          TO &dbz_user CONTAINER = ALL;
GRANT SELECT ANY TRANSACTION    TO &dbz_user CONTAINER = ALL;
GRANT SELECT_CATALOG_ROLE       TO &dbz_user CONTAINER = ALL;
GRANT EXECUTE_CATALOG_ROLE      TO &dbz_user CONTAINER = ALL;
GRANT FLASHBACK ANY TABLE       TO &dbz_user CONTAINER = ALL;
GRANT LOCK ANY TABLE            TO &dbz_user CONTAINER = ALL;
GRANT CREATE TABLE              TO &dbz_user CONTAINER = ALL;
GRANT CREATE SEQUENCE           TO &dbz_user CONTAINER = ALL;

GRANT EXECUTE ON DBMS_LOGMNR    TO &dbz_user CONTAINER = ALL;
GRANT EXECUTE ON DBMS_LOGMNR_D  TO &dbz_user CONTAINER = ALL;

-- -----------------------------------------------------------------------------
-- Views V_$ dinâmicas.
--
-- A lista varia um pouco entre versões do Oracle e entre versões do Debezium.
-- Conceder em bloco faria o init inteiro abortar por causa de uma única view
-- inexistente, então cada grant é tentado individualmente e o que falhar vira
-- um aviso visível no log do container — sem mascarar o problema.
-- -----------------------------------------------------------------------------
DECLARE
    TYPE t_views IS TABLE OF VARCHAR2(60);
    v_views  t_views := t_views(
        'V_$DATABASE',            'V_$LOG',                  'V_$LOG_HISTORY',
        'V_$LOGFILE',             'V_$ARCHIVED_LOG',         'V_$ARCHIVE_DEST_STATUS',
        'V_$LOGMNR_CONTENTS',     'V_$LOGMNR_LOGS',          'V_$LOGMNR_PARAMETERS',
        'V_$TRANSACTION',         'V_$MYSTAT',               'V_$STATNAME',
        'V_$THREAD',              'V_$PARAMETER',            'V_$NLS_PARAMETERS',
        'V_$INSTANCE',            'V_$SESSION',              'V_$LOGMNR_DICTIONARY_LOAD'
    );
    v_falhas PLS_INTEGER := 0;
BEGIN
    FOR i IN 1 .. v_views.COUNT LOOP
        BEGIN
            EXECUTE IMMEDIATE
                'GRANT SELECT ON ' || v_views(i) || ' TO &dbz_user CONTAINER = ALL';
        EXCEPTION WHEN OTHERS THEN
            v_falhas := v_falhas + 1;
            DBMS_OUTPUT.PUT_LINE(
                'AVISO: nao foi possivel conceder SELECT em ' || v_views(i) ||
                ' -> ' || SQLERRM);
        END;
    END LOOP;

    IF v_falhas = 0 THEN
        DBMS_OUTPUT.PUT_LINE('Todos os grants de views dinamicas concedidos.');
    ELSE
        DBMS_OUTPUT.PUT_LINE(v_falhas || ' grant(s) de view dinamica falharam ' ||
                             '(ver avisos acima). Confira antes de registrar o conector.');
    END IF;
END;
/

-- -----------------------------------------------------------------------------
-- Garante que o PDB reabra automaticamente após restart do container
-- -----------------------------------------------------------------------------
BEGIN
    EXECUTE IMMEDIATE 'ALTER PLUGGABLE DATABASE FREEPDB1 SAVE STATE';
EXCEPTION WHEN OTHERS THEN
    DBMS_OUTPUT.PUT_LINE('AVISO: SAVE STATE do FREEPDB1 falhou -> ' || SQLERRM);
END;
/

EXIT SUCCESS;
