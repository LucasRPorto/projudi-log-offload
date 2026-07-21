-- =============================================================================
-- 05 — projudi_historico.proc_cdc_mv
--
-- Desmembra o envelope Debezium lido de proc_cdc_kafka e materializa uma linha
-- por evento em proc_cdc.
--
-- Regras aplicadas:
--   * op = c (create) | u (update) | r (read, snapshot inicial)  -> usa `after`
--   * op = d (delete)                                            -> usa `before`
--     (num delete o `after` vem null; o `before` é a última imagem conhecida da
--      linha, que é justamente o que precisa ser preservado no histórico)
--   * o alias `env` absorve as duas formas do envelope: com o converter
--     configurado com schemas.enable=false o JSON já é o payload; com
--     schemas.enable=true ele vem dentro de "payload". Assim a MV não quebra se
--     a Frente C decidir ligar os schemas.
--
-- Conversões:
--   * datas   — o Debezium emite epoch em MILISSEGUNDOS (time.precision.mode=
--               connect no connector-proc.json); a divisão por 1000 propaga
--               NULL naturalmente.
--   * decimais— o connector usa decimal.handling.mode=double, então VALOR e
--               VALOR_CONDENACAO chegam como número JSON. accurateCastOrNull
--               leva para Decimal(20,2) sem estourar em valor inesperado.
--   * scn     — extraído como texto cru e desaspado, porque o Debezium ora
--               serializa o SCN como número, ora como string.
-- =============================================================================

CREATE MATERIALIZED VIEW IF NOT EXISTS projudi_historico.proc_cdc_mv
TO projudi_historico.proc_cdc
AS
WITH
    JSONExtractRaw(raw, 'payload')                      AS payload_raw,
    if(payload_raw != '', payload_raw, raw)             AS env,
    JSONExtractString(env, 'op')                        AS op,
    if(op = 'd', JSONExtractRaw(env, 'before'),
                 JSONExtractRaw(env, 'after'))          AS rec,
    JSONExtractRaw(env, 'source')                       AS src
SELECT
    JSONExtract(rec, 'ID_PROC',                        'UInt64')           AS ID_PROC,
    JSONExtract(rec, 'ID_PROC_DEPENDENTE',             'Nullable(UInt64)') AS ID_PROC_DEPENDENTE,
    JSONExtract(rec, 'PROC_NUMERO',                    'Nullable(UInt64)') AS PROC_NUMERO,
    JSONExtract(rec, 'DIGITO_VERIFICADOR',             'Nullable(UInt32)') AS DIGITO_VERIFICADOR,
    JSONExtract(rec, 'FORUM_CODIGO',                   'Nullable(UInt64)') AS FORUM_CODIGO,
    JSONExtract(rec, 'ANO',                            'Nullable(UInt32)') AS ANO,
    JSONExtract(rec, 'ID_PROC_TIPO',                   'Nullable(UInt64)') AS ID_PROC_TIPO,
    JSONExtract(rec, 'ID_PROC_PRIOR',                  'Nullable(UInt64)') AS ID_PROC_PRIOR,
    JSONExtract(rec, 'ID_PROC_FASE',                   'Nullable(UInt64)') AS ID_PROC_FASE,
    JSONExtract(rec, 'ID_PROC_STATUS',                 'Nullable(UInt64)') AS ID_PROC_STATUS,
    JSONExtract(rec, 'ID_SERV',                        'Nullable(UInt64)') AS ID_SERV,
    JSONExtract(rec, 'ID_SERV_ORIGEM',                 'Nullable(UInt64)') AS ID_SERV_ORIGEM,
    JSONExtract(rec, 'ID_AREA',                        'Nullable(UInt32)') AS ID_AREA,
    JSONExtract(rec, 'ID_OBJETO_PEDIDO',               'Nullable(UInt64)') AS ID_OBJETO_PEDIDO,
    JSONExtract(rec, 'ID_CLASSIFICADOR',               'Nullable(UInt64)') AS ID_CLASSIFICADOR,
    JSONExtract(rec, 'ID_PROC_SIT',                    'Nullable(UInt64)') AS ID_PROC_SIT,
    JSONExtract(rec, 'TCO_NUMERO',                     'Nullable(String)') AS TCO_NUMERO,
    JSONExtract(rec, 'SEGREDO_JUSTICA',                'Nullable(UInt16)') AS SEGREDO_JUSTICA,
    JSONExtract(rec, 'APENSO',                         'Nullable(UInt16)') AS APENSO,
    accurateCastOrNull(JSONExtract(rec, 'VALOR', 'Nullable(Float64)'), 'Decimal(20, 2)') AS VALOR,
    toDateTime64(JSONExtract(rec, 'DATA_RECEBIMENTO',      'Nullable(Int64)') / 1000, 3) AS DATA_RECEBIMENTO,
    toDateTime64(JSONExtract(rec, 'DATA_ARQUIVAMENTO',     'Nullable(Int64)') / 1000, 3) AS DATA_ARQUIVAMENTO,
    JSONExtract(rec, 'PROC_DIRETORIO',                 'Nullable(String)') AS PROC_DIRETORIO,
    JSONExtract(rec, 'CODIGO_TEMP',                    'Nullable(UInt64)') AS CODIGO_TEMP,
    JSONExtract(rec, 'PROC_NUMERO_ANTIGO_TEMP',        'Nullable(UInt64)') AS PROC_NUMERO_ANTIGO_TEMP,
    JSONExtract(rec, 'CODRE_CURSO_TEMP',               'Nullable(UInt64)') AS CODRE_CURSO_TEMP,
    JSONExtract(rec, 'TABELA_ORIGEM_TEMP',             'Nullable(String)') AS TABELA_ORIGEM_TEMP,
    JSONExtract(rec, 'EFEITO_SUSPENSIVO',              'Nullable(UInt16)') AS EFEITO_SUSPENSIVO,
    JSONExtract(rec, 'PENHORA',                        'Nullable(UInt16)') AS PENHORA,
    toDateTime64(JSONExtract(rec, 'DATA_TRANSITO_JULGADO', 'Nullable(Int64)') / 1000, 3) AS DATA_TRANSITO_JULGADO,
    JSONExtract(rec, 'JULGADO_2_GRAU',                 'Nullable(UInt8)')  AS JULGADO_2_GRAU,
    accurateCastOrNull(JSONExtract(rec, 'VALOR_CONDENACAO', 'Nullable(Float64)'), 'Decimal(20, 2)') AS VALOR_CONDENACAO,
    JSONExtract(rec, 'ID_CUSTA_TIPO',                  'Nullable(UInt16)') AS ID_CUSTA_TIPO,
    JSONExtract(rec, 'ID_AREA_DIST',                   'Nullable(UInt64)') AS ID_AREA_DIST,
    toDateTime64(JSONExtract(rec, 'DATA_DIGITALIZACAO',    'Nullable(Int64)') / 1000, 3) AS DATA_DIGITALIZACAO,
    JSONExtract(rec, 'PROCESSO_FISICO_TIPO',           'Nullable(String)') AS PROCESSO_FISICO_TIPO,
    JSONExtract(rec, 'PROCESSO_FISICO_NUMERO',         'Nullable(String)') AS PROCESSO_FISICO_NUMERO,
    JSONExtract(rec, 'PROCESSO_FISICO_COMARCA_CODIGO', 'Nullable(UInt64)') AS PROCESSO_FISICO_COMARCA_CODIGO,
    JSONExtract(rec, 'LOCALIZADOR',                    'Nullable(String)') AS LOCALIZADOR,
    toDateTime64(JSONExtract(rec, 'DATA_PRESCRICAO',       'Nullable(Int64)') / 1000, 3) AS DATA_PRESCRICAO,
    JSONExtract(rec, 'DIGITAL100',                     'Nullable(UInt8)')  AS DIGITAL100,
    JSONExtract(rec, 'ID_PROC_PRIOR_1',                'Nullable(UInt64)') AS ID_PROC_PRIOR_1,
    JSONExtract(rec, 'ID_CLASSIFICADOR2',              'Nullable(UInt64)') AS ID_CLASSIFICADOR2,

    toLowCardinality(if(op != '', op, 'r'))                              AS cdc_op,
    toUInt64(ifNull(JSONExtract(env, 'ts_ms', 'Nullable(Int64)'), 0))    AS cdc_ts_ms,
    replaceAll(JSONExtractRaw(src, 'scn'), '"', '')                      AS cdc_scn,
    now()                                                                AS ingestion_ts
FROM projudi_historico.proc_cdc_kafka
WHERE rec != '' AND rec != 'null';
