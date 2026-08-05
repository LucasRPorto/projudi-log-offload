-- =============================================================================
-- 06 — Fast Recovery Area: destino dentro do volume + teto explícito
--
-- &1 = tamanho desejado (ex.: 4G), vindo de ORACLE_FRA_SIZE no .env
-- &2 = diretório da FRA (deve existir e ser gravável pelo usuário oracle;
--      o 01_init_projudi.sh cria antes de chamar este script)
--
-- POR QUE ISTO EXISTE
-- -------------------
-- Medido em 2026-08-05, no container de pé:
--
--   db_recovery_file_dest ......... NULO
--   db_recovery_file_dest_size .... 0
--   v$recovery_file_dest .......... ZERO linhas
--   v$archive_dest (dest 1) ....... /opt/oracle/product/26ai/dbhomeFree/dbs/arch
--                                    status VALID, binding MANDATORY
--
-- Ou seja: a imagem gvenzl/oracle-free sobe SEM Fast Recovery Area. Com
-- ARCHIVELOG ligado (script 05, pré-requisito do LogMiner/Debezium), os
-- archived redo logs vão para $ORACLE_HOME/dbs/arch. Isso são DOIS problemas:
--
--   1. PERSISTÊNCIA — dbs/arch está na camada gravável do container, FORA do
--      volume oracle-data. Os archived logs somem quando o container é
--      recriado, e o conector Debezium que dependia deles quebra com
--      ORA-01291 (missing logfile). É a mesma classe de falha da decisão 18,
--      agora sobre o redo em vez do datafile.
--
--   2. TETO — sem FRA não há `db_recovery_file_dest_size` valendo para nada.
--      O arquivamento só encontra limite quando o disco do host acaba. Com
--      binding MANDATORY, o banco trava quando isso acontece.
--
-- Ver docs/decisoes.md, decisão 27.
--
-- ORDEM IMPORTA: `db_recovery_file_dest_size` PRIMEIRO. Definir o destino sem
-- o tamanho falha com ORA-19802 (cannot use DB_RECOVERY_FILE_DEST without
-- DB_RECOVERY_FILE_DEST_SIZE).
--
-- Não é preciso mexer em log_archive_dest_1: verificado em execução real que,
-- ao definir db_recovery_file_dest, o destino 1 migra sozinho de dbs/arch para
-- USE_DB_RECOVERY_FILE_DEST e troca de MANDATORY para OPTIONAL.
--
-- O QUE O TETO FAZ — E O QUE ELE NÃO FAZ
-- --------------------------------------
-- Ele NÃO impede o acúmulo: os archived logs continuam se acumulando na mesma
-- velocidade, porque nada os apaga sozinho. O que ele faz é TROCAR O MODO DE
-- FALHA. Sem teto, a FRA cresce até esgotar o disco do host e derruba tudo o
-- que roda nele. Com teto, ao atingir o limite o Oracle recusa novos archived
-- logs (ORA-19809 / ORA-19804) e suspende as escritas: o banco trava, o host
-- sobrevive, e o diagnóstico aparece no alert log em vez de num congelamento
-- de sistema operacional.
--
-- Falha contida e legível é melhor que falha difusa. Mas é contenção, não
-- solução: a solução é limpar, com `make limpar-archivelog`. O `make disco`
-- e o item `f` do `make validate` avisam antes de qualquer um dos dois.
-- =============================================================================

WHENEVER OSERROR EXIT FAILURE

-- CONTINUE, e não EXIT: este é um ajuste de segurança operacional, não um
-- pré-requisito funcional. Se falhar, o schema PROJUDI e as duas soluções
-- continuam válidos — perdemos a contenção, não a funcionalidade. Abortar o
-- init inteiro por causa disso trocaria um risco por uma certeza.
WHENEVER SQLERROR CONTINUE NONE

SET ECHO OFF
SET FEEDBACK OFF
SET VERIFY OFF
SET SERVEROUTPUT ON

ALTER SYSTEM SET db_recovery_file_dest_size = &1 SCOPE=BOTH;
ALTER SYSTEM SET db_recovery_file_dest = '&2' SCOPE=BOTH;

DECLARE
    v_limite   NUMBER;
    v_usado    NUMBER;
    v_destino  VARCHAR2(512);
    v_dest_arq VARCHAR2(512);
BEGIN
    BEGIN
        SELECT name, space_limit, space_used
          INTO v_destino, v_limite, v_usado
          FROM v$recovery_file_dest
         WHERE ROWNUM = 1;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            DBMS_OUTPUT.PUT_LINE('[fra] ATENCAO: nenhuma FRA configurada apos o ALTER SYSTEM.');
            DBMS_OUTPUT.PUT_LINE('[fra] os archived logs vao para dbs/arch, fora do volume,');
            DBMS_OUTPUT.PUT_LINE('[fra] sem teto e sem sobreviver a recriacao do container.');
            RETURN;
    END;

    DBMS_OUTPUT.PUT_LINE('[fra] destino ....... ' || v_destino);
    DBMS_OUTPUT.PUT_LINE('[fra] limite ........ ' || ROUND(v_limite / 1024 / 1024) || ' MB');
    DBMS_OUTPUT.PUT_LINE('[fra] em uso ........ ' || ROUND(v_usado  / 1024 / 1024) || ' MB');

    SELECT destination INTO v_dest_arq
      FROM v$archive_dest
     WHERE dest_id = 1;
    DBMS_OUTPUT.PUT_LINE('[fra] arquivamento .. ' || v_dest_arq);

    IF v_destino NOT LIKE '/opt/oracle/oradata/%' THEN
        DBMS_OUTPUT.PUT_LINE('[fra] ATENCAO: a FRA esta FORA do volume oracle-data.');
    END IF;
END;
/

EXIT SUCCESS;
