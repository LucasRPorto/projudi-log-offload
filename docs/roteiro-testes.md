# Roteiro de testes — máquina de referência (32 GB)
*Cada resultado vai para `validacao/evidencias/` com nome datado. Esses outputs são os números e figuras da banca.*

---

## T1 — Ambiente do zero (10 min)
```bash
make reset && make up && make validate
```
**O que faz:** destrói tudo e reconstrói do nada, provando que o ambiente é 100% reproduzível.
**Aprovado se:** 32/32 ✅.
**Para a banca:** "qualquer avaliador reproduz o experimento com um comando."

## T2 — Registrar o conector CDC (5 min)
```bash
./scripts/register-connector.sh
curl -s localhost:8083/connectors/projudi-proc-connector/status | python3 -m json.tool
```
**O que faz:** liga o observador (Debezium) que captura mudanças do Oracle.
**Aprovado se:** `RUNNING` no conector **e** na task.
**Bônus imediato:** conferir se os 5 UPDATEs pendentes do Lucas (VALOR 111–555) chegam sozinhos ao ClickHouse — se chegarem, é resiliência provada até contra crash de host.

## T3 — Benchmark oficial da Solução 1 (20 min) ⭐ número principal da banca
```bash
cd log-writer && mvn -q test-compile exec:java@bench \
  -Dbench.n=20000 -Dbench.lotes=1,100,500,2000 -Dbench.repeticoes=5 \
  -Dbench.saida=../validacao/evidencias/bench-oficial-$(date +%Y%m%d).txt
```
**O que faz:** grava 20 mil logs no Oracle e no ClickHouse lado a lado (mesmo host = comparação justa) e cronometra. Lote=1 é o modo síncrono de hoje; lote=2000 é o modo com fila.
**Aprovado se:** completa as 5 repetições e a completude bate (N linhas = N IDs) nos dois destinos.
**Para a banca:** a tabela e a razão Oracle/ClickHouse — o gráfico central do relatório.

## T4 — Latência do CDC, 3 medições (15 min) ⭐ número da Solução 2
```bash
make sql   # UPDATE em 1 processo + COMMIT, anotar o horário exato
make ch    # consultar proc_cdc até o evento aparecer, anotar o horário
```
**O que faz:** mede o tempo entre a mudança acontecer no Oracle e aparecer no destino.
**Repetir 3×** e reportar as três (não a média escondendo variação).
**Aprovado se:** todas na casa de segundos (~5–60s é o esperado do LogMiner).
**Para a banca:** "a mudança aparece no histórico auditável em X segundos, sem código novo."

## T5 — Completude sob rajada (10 min)
```bash
make sql   # 100 UPDATEs em sequência + COMMIT
make ch    # SELECT count() dos eventos novos
```
**O que faz:** prova que sob volume nada se perde nem duplica.
**Aprovado se:** 100 operações → exatamente 100 eventos.

## T6 — Resiliência: pipeline desligado (15 min) ⭐ argumento de confiabilidade
```bash
docker stop projudi-connect
make sql   # 50 UPDATEs + COMMIT
docker start projudi-connect   # esperar ~90s
make ch    # conferir os 50
```
**O que faz:** derruba a peça central do pipeline, gera mudanças no escuro, religa.
**Aprovado se:** os 50 eventos chegam após religar — zero perdas.
**Para a banca:** "o sistema pode cair; a auditoria não perde nada."

## T7 — Ensaio da demo ao vivo (10 min)
Executar exatamente o que será feito na defesa: 1 UPDATE → 30s de espera → consulta mostrando o evento. Cronometrar e **tirar o print de backup** (se a demo falhar na hora, o print salva).

## T8 — Documento de extensão (30 min, escrita)
Criar `docs/extensao-cdc.md`: passo a passo para adicionar QUALQUER outra tabela ao pipeline (supplemental logging na tabela → include.list do conector → DDLs ClickHouse → MV).
**Por que é teste:** valida que a solução é genérica, não um truque para uma tabela — entregável central do TCC.

---

## Higiene (não pular)
- **Disco:** ARCHIVELOG acumula sem limpeza e derruba o host (aconteceu com o Lucas). Monitorar `df -h` entre os testes; se crescer, registrar e criar `make limpar-archivelog`.
- **Tudo que travar:** anotar erro + solução em `docs/decisoes.md` — os empecilhos são material do relatório, não vergonha.

## O que NÃO precisa testar (para não perder tempo)
- Testes unitários do log-writer (68 verdes, já validados).
- Integração dos 3 formatos de payload (validada na máquina do Lucas).
- Snapshot inicial do CDC (validado — as 6 linhas op='r').
- Alteração na LogPs do Projudi (fase posterior ao MVP, fora da defesa).
