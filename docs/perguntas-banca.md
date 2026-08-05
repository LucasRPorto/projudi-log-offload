# Perguntas de banca

Documento vivo. Cada entrada tem: a **pergunta** como a banca a formularia, uma
**resposta curta** (o que se fala em voz alta, 2 a 4 frases), e o **fundamento**
(decisão numerada, `arquivo:linha`, evidência ou número medido).

**Regra deste arquivo:** quando não há resposta fechada, isso é dito
explicitamente, junto com o que falta medir ou decidir. Lacuna identificada vale
mais que resposta inventada — e uma banca reconhece a diferença.

**Estado:** semeado em 2026-08-05, ao fim da Fase 0. As seções marcadas
`⏳ a preencher` dependem de blocos do tour de código ou de testes ainda não
executados. Elas estão listadas de propósito: a lista das perguntas que ainda
não sei responder é parte do documento, não uma pendência a esconder.

---

## 1. Metodologia e validação

### "Vocês testaram, ou só verificaram?"

**Resposta.** As duas coisas, e o trabalho registra os casos em que a diferença
apareceu. Cinco suposições passaram por revisão estática completa e só caíram em
execução real; estão documentadas com o erro mantido, não corrigido em silêncio.
É a razão de o `docs/decisoes.md` ter 1400 linhas.

**Fundamento.** `docs/decisoes.md`, decisões 5, 18, 25, 26 e 27. A lista
consolidada e numerada está na seção 8 deste documento.

### "O que aconteceu com o benchmark de 04/08?"

**Resposta.** Foi interrompido por uma falha de infraestrutura — o disco do host
de desenvolvimento encheu e a máquina travou. O arquivo existe mas para na
primeira medição, sem nenhuma repetição concluída; foi **descartado** como
número oficial em vez de ser apresentado como resultado parcial.

**Fundamento.** `validacao/evidencias/bench-oficial-20260804.txt` termina em
`Medindo ClickHouse com lote=1...`. Decisão 27, seção "Consequência colateral".

### "Por que o ambiente é medido numa máquina de 8 GB?"

**Resposta.** Não deveria ser, e o repositório diz isso desde antes desta sessão:
`docs/ambientes.md` §6 define que a máquina de 8 GB serve para `make up-lite` e
**nunca** para medição, porque sem o Oracle local não existe grupo de controle.

⏳ **Lacuna declarada.** A Fase 3 desta sessão tenta a bateria completa nesta
máquina mesmo assim, com o teto de RAM do WSL elevado de 3,7 GiB para 6 GB. O
que não couber será registrado como "não medido", com o motivo técnico e o
comando para reproduzir em máquina maior — não como resultado degradado.

**Fundamento.** `docs/ambientes.md:244-254` e `:277-288`.

---

## 2. Limitações, riscos e produção

### "O supplemental logging não onera o Oracle que vocês querem aliviar?"

**Resposta curta.** Onera, e o trabalho assume isso: `(ALL) COLUMNS` na `PROC`
aumenta o redo gerado a cada `UPDATE`. O argumento não é que o custo inexiste, é
que ele é **assimétrico**: redo é transitório e reciclado, enquanto a tabela
`LOG` é permanente e nunca pode ser purgada; e o custo é seletivo por tabela, não
um imposto sobre o banco inteiro.

⏳ **Falta o número.** A medição de `redo size` em `v$sysstat`, antes e depois de
N `UPDATE`s, comparada com carga equivalente em tabela sem `(ALL) COLUMNS`, está
planejada na Fase 3 e **ainda não foi executada**. Sem ela, a resposta acima é
argumento, não evidência — e deve ser apresentada como tal.

**Fundamento.** Decisão 6 já previa a medição: *"é justamente um dos números que
a Frente C deve medir"* (`docs/decisoes.md:158-160`).

### "O CDC impõe alguma restrição operacional ao banco de origem?"

**Resposta.** Sim, e ela foi descoberta em execução: os archived redo logs deixam
de ser descartáveis a critério do DBA e passam a ter um consumidor com posição
própria. Apagar um archived log que o conector ainda não consumiu quebra o
pipeline com ORA-01291 e exige re-registrar o conector, perdendo a posição.

**Fundamento.** Decisão 27. A limpeza implementada (`make limpar-archivelog`)
preserva por padrão uma janela de 1 hora exatamente por isso.

### "E se o ClickHouse cair? E se a JVM cair com a fila cheia?"

⏳ **A preencher no Bloco 4 do tour** (fallback Oracle e política de saturação).
O material existe: decisão 19, seções "O que a solução garante — e o que não
garante", "Política de saturação" e "Limitação conhecida: a semântica de
transação muda" (`docs/decisoes.md:574-660`). A resposta precisa citar a janela
de perda **em números**, e eu ainda não li essas seções nesta sessão — não vou
resumi-las de memória.

### "Números de laboratório valem para produção?"

**Resposta.** Não como previsão absoluta. São comparativos entre duas
alternativas no **mesmo** ambiente controlado, mesma máquina, mesma pilha de
rede, mesma janela de tempo — o que isola a variável de interesse. Por isso o
plano de implantação começa em modo sombra (`AMBOS`), comparando registro a
registro em produção antes de qualquer corte.

**Fundamento.** `docs/ambientes.md:79-122` (por que o Oracle em container é o
grupo de controle, e não redundância). Decisão 21 (feature flag de três estados).

---

## 3. Escolhas de tecnologia

### "Isso é um data lake?"

**Resposta.** Não, e o título da primeira entrega ficou desatualizado. O conceito
inicial **era** um data lake (Iceberg + MinIO + Parquet + Trino) e foi abandonado
no refinamento por requisitos explícitos: nó único, sem object storage, SQL
nativo e operável pela equipe do TJ. O artefato é um banco analítico colunar.
Vale registrar que a camada `log_raw` preserva a característica de *schema-on-read*
típica de lake — payloads crus, sem parsing, esquema aplicado na leitura.

⏳ **Verificar antes da defesa.** Preciso confirmar no repositório onde a
transição Iceberg/MinIO/Trino → ClickHouse está registrada, para citar a decisão
numerada em vez de contar a história de memória. Não localizei essa decisão no
`docs/decisoes.md` durante a Fase 0.

### "Por que Kafka e Debezium, se a Solução 1 grava direto via JDBC?"

**Resposta.** Porque são duas soluções **independentes** que compartilham um
único destino analítico. Kafka e Debezium existem apenas para a Solução 2, que
captura o efeito no banco sem tocar no código do Projudi. A Solução 1 registra a
intenção do usuário e não usa nenhum dos dois.

**Fundamento.** `docs/arquitetura.md`; `infra/docker-compose.yml:120-201`
(Kafka e Connect só participam do caminho de CDC).

### "Por que ClickHouse e não Oracle particionado ou arquivado?"

**Resposta.** Porque o particionamento anual **já existe** em produção
(`LOG_2010..LOG_2025`) e é justamente o paliativo atual. Ele não resolve nenhuma
das três dores: continua no mesmo banco competindo por recursos com as
transações judiciais, continua custando armazenamento caro, e continua com o
conteúdo em texto corrido proprietário nos CLOBs.

⏳ **Fundamento a completar.** Preciso localizar onde o repositório documenta o
particionamento atual em produção para citar `arquivo:linha`.

---

## 4. CDC e Oracle

### "Como isso se estende a outras tabelas do Projudi?"

⏳ **Resposta pendente do entregável.** `docs/extensao-cdc.md` **ainda não
existe** — confirmado em 2026-08-05. Ele é o T8 da Fase 3, e o plano é escrevê-lo
e então **validá-lo executando** sobre uma segunda tabela do laboratório. Guia de
extensão não executado é promessa; executado é prova.

### "Por que o ARCHIVELOG não é ligado por variável de ambiente?"

**Resposta.** Porque a variável `ENABLE_ARCHIVELOG` não existe no entrypoint da
imagem `gvenzl/oracle-free` — isso foi suposto, verificado no código da imagem e
confirmado em execução real, com o banco subindo em NOARCHIVELOG mesmo com a
variável definida. A troca é feita por uma sequência explícita de
`SHUTDOWN / STARTUP MOUNT / ALTER DATABASE ARCHIVELOG / OPEN`.

**Fundamento.** Decisão 5; `infra/oracle/init/sql/05_enable_archivelog.sql`.

### "Onde ficam os archived redo logs, e quem os apaga?"

**Resposta.** Ficam numa Fast Recovery Area criada pelo init **dentro do volume**
`oracle-data`, com teto de 4G, e são apagados sob demanda por
`make limpar-archivelog`. Nada disso vinha de fábrica: a imagem sobe sem FRA
nenhuma, arquivando na camada gravável do container — onde os arquivos não
sobrevivem a uma recriação e não encontram teto antes de o disco do host acabar.

**Fundamento.** Decisão 27; `infra/oracle/init/sql/06_fra_size.sql`. Verificado
em execução: ao definir `db_recovery_file_dest`, o destino 1 migra sozinho de
`dbs/arch` para `USE_DB_RECOVERY_FILE_DEST`.

### "Vocês usaram RMAN para a limpeza?"

**Resposta.** Não, porque não há RMAN: a imagem `23-slim-faststart` o remove. A
limpeza usa `SYS.DBMS_BACKUP_RESTORE.deleteArchivedLog`, o pacote que o próprio
RMAN chama por baixo, e que apaga o arquivo do disco **e** baixa o registro no
controlfile — o par que mantém a contabilidade de espaço correta.

**Fundamento.** Decisão 27, defeito 3. Verificado: `ls $ORACLE_HOME/bin/rman` →
*No such file or directory*.

---

## 5. Decisões do `log-writer`

⏳ **Seção inteira a preencher no Bloco 4 do tour.** Perguntas já identificadas,
sem resposta redigida:

- Por que IDs Snowflake gerados no cliente, e não a sequence do Oracle? (decisão 20)
- Como o `workerId` é atribuído, e o que acontece se dois nós receberem o mesmo?
- Por que o formato dos CLOBs foi **preservado** em vez de estruturado? (mudar
  destino e formato ao mesmo tempo impediria atribuir diferença a uma causa só)
- O que exatamente o modo `AMBOS` garante, e o que ele não garante? (decisão 21)
- Por que Java 8 e `clickhouse-jdbc 0.7.2`? (decisão 14)
- Por que `ojdbc8` no benchmark e `ojdbc11` no Connect? (decisão 22)
- Por que o `slf4j-api` é obrigatório? (decisão 25 — o driver falha em silêncio sem ele)

---

## 6. Arquitetura e modelagem

⏳ **A preencher no Bloco 3 do tour.** Perguntas já identificadas:

- Por que `JSONAsString` na Kafka engine, e não `JSONEachRow`? (decisão 7)
- Por que o histórico é append-only em vez de aplicar UPDATEs? (decisão 7)
- Por que `NUMBER(24) → UInt64`? (decisão 9)
- Por que a nulabilidade é assimétrica entre `log_raw` e `proc_cdc`? (decisão 10)
- Por que `ID_LOG_TIPO` é resolvido no ClickHouse e não no Oracle? (decisão 24)
- Justificativa do `ORDER BY`, da partição e dos codecs de `log_raw`.

---

## 7. Escopo e terminologia

### "Por que a alteração no Projudi não foi aplicada?"

**Resposta.** Por escopo institucional, não por limitação técnica: mexer na
`LogPs` em produção depende da governança do TJ-GO e de uma janela de
homologação. A alteração está documentada e é pequena.

⏳ **Verificar antes da defesa.** O número "~14 linhas na `LogPs`" **não foi
localizado** em nenhum arquivo do repositório durante a Fase 0. Ou ele existe num
lugar que não varri, ou é um número que circula fora da documentação — em
qualquer dos casos, não deve ser dito à banca antes de ser localizado e conferido
contra o código.

### "O repositório documenta 64 testes; vocês falam em 68. Qual é?"

⏳ **Divergência real, a resolver.** `docs/arquitetura.md:186` e `README.md:382`
dizem "64 testes unitários verdes" e "gravação e benchmark aguardam ambiente" —
texto anterior à validação com integração real. O número corrente citado é 68,
com integração ligada. **A suíte será executada e o número conferido** antes de
qualquer atualização do texto; até lá, nenhum dos dois deve ser afirmado.

---

## 8. A lista consolidada das suposições que só caíram em execução real

⏳ **A fechar ao final do Bloco 6 do tour**, com a numeração exata para a
monografia. Levantamento parcial da Fase 0, a conferir e completar:

| # | Suposição | Onde caiu | Decisão |
|---|---|---|---|
| 1 | `ENABLE_ARCHIVELOG` seria interpretada pela imagem | `make validate` acusou NOARCHIVELOG | 5 |
| 2 | `healthy` do Docker significaria "pronto para consulta" | consultas vazias na janela de carga de metadados | 16 |
| 3 | Silenciar a saída de um comando seria inócuo | escondeu prompt interativo e travou o script | 16 |
| 4 | Nome de datafile relativo equivaleria a absoluto | banco morreu na 1ª recriação de container, após 30 itens verdes | 18 |
| 5 | O driver do ClickHouse falharia com erro visível sem `slf4j-api` | falhou em silêncio | 25 |
| 6 | Montar `config.d/` inteiro seria equivalente a montar arquivo a arquivo | escondeu `docker_related_config.xml`; healthy por dentro, inacessível por fora | 26 |
| 7 | A causa do disco cheio seria o acúmulo de archived logs | eram 90 MB; o disco foi enchido por ~33,7 GB de lixo do Docker | 27 |
| 8 | Existiria uma FRA para encher, e um `rman` para limpá-la | não existia nenhum dos dois | 27 |

**Nota metodológica.** As linhas 1 a 6 seguem um padrão: a suposição sobrevive à
revisão estática e cai na execução. As linhas 7 e 8 são de outra natureza — o
risco estava **documentado por extenso e no lugar certo**, mas o remédio nunca
foi executado, e por isso nunca foi verificado. Verificação estática não
substitui execução; documentação não substitui automação. São dois degraus
distintos, e o trabalho tem exemplo empírico de cada um.
