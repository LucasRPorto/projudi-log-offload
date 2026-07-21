-- =============================================================================
-- 60 — PDB FREEPDB1: supplemental logging da PROJUDI.PROC
--
-- Parâmetro posicional:
--   1 = usuário comum do Debezium
--
-- ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS faz o Oracle gravar no redo o valor
-- de TODAS as colunas a cada UPDATE, e não apenas das que mudaram. É o que
-- permite ao Debezium montar um `after` completo — sem isso, um UPDATE de uma
-- coluna produziria um evento com as outras 42 nulas, e o histórico da
-- Solução 2 ficaria inutilizável.
--
-- O custo é redo maior: é uma decisão consciente, registrada em
-- docs/decisoes.md, e aplicada a UMA tabela apenas.
-- =============================================================================

WHENEVER SQLERROR EXIT SQL.SQLCODE
WHENEVER OSERROR  EXIT FAILURE
SET ECHO ON
SET VERIFY OFF

DEFINE dbz_user = &1

ALTER TABLE PROJUDI.PROC ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

-- O usuário do Debezium já tem SELECT ANY TABLE (concedido com CONTAINER=ALL),
-- mas o grant explícito documenta a dependência e sobrevive a um eventual
-- endurecimento dos privilégios genéricos.
GRANT SELECT ON PROJUDI.PROC TO &dbz_user;

EXIT SUCCESS;
