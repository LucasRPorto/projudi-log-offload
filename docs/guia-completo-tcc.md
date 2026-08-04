# Guia completo do TCC — para entender, explicar e apresentar
### Modernização da arquitetura de logs de auditoria do Projudi (TJ-GO)
*Documento de referência pessoal — Lucas — agosto/2026*

---

# PARTE 1 — A explicação simples (leia esta primeiro)

## O problema, contado como uma história

O Projudi é o sistema onde tramitam os processos judiciais de Goiás. Além de fazer o trabalho, ele anota **tudo** o que acontece num "caderno de auditoria": quem entrou, o que mudou, quando mudou. Esse caderno é uma tabela chamada `LOG`, e ela mora dentro do banco Oracle — o mesmo banco, caro e crítico, que sustenta a operação judicial.

Isso gera três dores:

1. **O caderno disputa espaço com o trabalho.** Cada anotação de log compete com as operações reais dos processos pelos mesmos recursos do banco. Quando o caderno pesa, o sistema inteiro sente.
2. **O caderno nunca para de crescer e nunca pode ser jogado fora.** Auditoria é obrigação legal. São 15+ anos de histórico ocupando o armazenamento mais caro que o tribunal tem.
3. **O caderno anota mal.** As mudanças são registradas como texto corrido num formato inventado há 20 anos (`[campo:valor;campo:valor]`). Perguntar "como estava o processo X em tal data?" exige decifrar texto, não consultar dados.

## As duas soluções (e por que são duas)

**Solução 1 — Mudar o caderno de lugar.** Os registros de log passam a ser gravados num banco especializado nisso (ClickHouse): muito mais barato, muito mais rápido para consultas de auditoria. O conteúdo continua idêntico — só muda o endereço. É como tirar o arquivo morto de dentro do cofre e colocá-lo num galpão climatizado feito para arquivos: mesma papelada, custo de guarda muito menor.

**Solução 2 — Um escrivão automático.** Em vez de depender de cada programador lembrar de anotar as mudanças (e anotar naquele formato ruim), instalamos um observador (Debezium) que fica olhando o próprio banco. Toda alteração numa tabela importante é capturada **na fonte**, automaticamente, com "antes" e "depois" organizados — **sem mudar uma linha do Projudi**.

Elas não competem: a Solução 1 registra a **intenção** ("o usuário fulano clicou em arquivar"), a Solução 2 registra o **efeito** ("a linha do processo mudou de status A para B"). Auditoria completa precisa das duas.

## O que já foi construído (as três frentes)

O trabalho foi dividido em três frentes para poder andar em paralelo:

**Frente A — O terreno e a fundação (pronta e testada).** Um ambiente completo que sobe com um comando: um Oracle em miniatura idêntico ao de produção, o ClickHouse de destino, e toda a tubulação entre eles (Kafka, Debezium). 32 verificações automáticas provam que tudo está de pé. Qualquer pessoa — inclusive a banca — reproduz isso em minutos.

**Frente B — O carteiro dos logs (pronta e testada).** Uma biblioteca Java chamada `log-writer` que sabe entregar os registros de log no ClickHouse. Ela tem três mecanismos de segurança pensados para um tribunal: um **interruptor de três posições** (só Oracle / só ClickHouse / os dois ao mesmo tempo, para comparar antes de confiar); uma **rede de proteção** (se o ClickHouse cair, os registros voltam para o Oracle — nada se perde por indisponibilidade); e **honestidade documentada** (a janela exata de risco em cada cenário está escrita, com números).

**Frente C — O escrivão automático (funcionando, falta medir).** O pipeline de captura automática. Na SUA máquina, neste fim de semana, ele rodou de ponta a ponta: você alterou processos no Oracle e viu as mudanças aparecerem sozinhas no ClickHouse segundos depois.

## O que VOCÊ provou nos seus testes (isso é importante — foi você que provou)

| O que você rodou | O que provou, em português |
|---|---|
| `make validate` — 32 ✅ | A fundação inteira existe e funciona na sua máquina |
| `mvn test` — 68 testes, 0 falhas | O carteiro entrega os registros **byte a byte idênticos**, acentos intactos — fidelidade total, essencial para auditoria judicial |
| Benchmark reduzido — 350/350 IDs | O carteiro grava nos DOIS destinos sem perder nem duplicar nada; de brinde, o ClickHouse foi 1.6x mais rápido até na sua máquina fraca |
| Consulta com 6 linhas `op='r'` | O escrivão automático fotografou o estado inicial das tabelas e entregou no destino |
| Consulta com `u`, `u`, `d` | **O momento mágico:** suas alterações no Oracle apareceram sozinhas no ClickHouse — capturadas do banco, sem código novo |
| Teste de resiliência | Inconcluso na sua máquina (o PC travou) — os 5 registros estão esperando no Oracle; o colega finaliza |

## Por que o Projudi ainda não foi alterado (não é esquecimento)

- Na **Solução 2**, o Projudi **nunca** será alterado — esse é o argumento de venda dela.
- Na **Solução 1**, a alteração é a última peça **de propósito**: primeiro se constrói e prova o destino, depois se pluga. A alteração já está desenhada (~14 linhas na classe `LogPs`, documentadas no README do log-writer) e será feita em fase posterior ao MVP, começando pelo modo sombra em homologação.

Regra de ouro do projeto: **nunca arriscar o sistema real para testar o experimento.**

---

# PARTE 2 — Andamento e foco

## Percentual honesto do TCC

| Bloco | Peso | Concluído | Contribui |
|---|---|---|---|
| Frente A — Infraestrutura | 20% | 100% | 20% |
| Frente B — log-writer | 20% | 90% (falta só o benchmark oficial) | 18% |
| Frente C — Pipeline CDC | 20% | 75% (funciona; faltam medições oficiais, resiliência e doc de extensão) | 15% |
| Documento escrito (Overleaf) | 25% | 10% (matéria-prima pronta, texto não) | 2,5% |
| Apresentação + ensaio da defesa | 15% | 0% | 0% |
| **TOTAL** | 100% | | **~55%** |

Leitura: **o desenvolvimento está ~90% pronto; o que falta do TCC é majoritariamente escrita e defesa.** E isso muda seu foco:

## Onde você deve focar AGORA

1. **Escrita do Overleaf** — é o novo caminho crítico e é seu. O desenvolvimento restante está nas mãos do colega; se você esperar por ele para escrever, o cronograma trava. Você já tem o prompt pronto de sessões anteriores para gerar o documento; a matéria-prima (decisões, problemas, evidências) está toda no repositório.
2. **Evidências organizadas** — salve os prints/outputs dos seus testes em `validacao/evidencias/` com nomes datados. São as figuras do relatório e os slides da demo.
3. **Repassar a bateria ao colega** (mensagem pronta na Parte 3).
4. **Ensaiar a narrativa da banca** (roteiro na Parte 4) — você acabou de me dizer que não sabia explicar o que foi feito; depois de ler este guia, a melhor forma de fixar é explicar em voz alta uma vez.

---

# PARTE 3 — Mensagem pronta para o colega

> A infraestrutura e as duas soluções já rodaram de ponta a ponta na minha máquina de 8 GB — sua sessão não é para descobrir se funciona, é para **medir** no ambiente de referência (sua máquina de 32 GB). O roteiro:
>
> 1. Ambiente do zero: `make reset && make up && make validate` → tem que dar 32/32.
> 2. Registrar o conector CDC: `./scripts/register-connector.sh` → conector E task em RUNNING.
> 3. **Benchmark oficial (Solução 1):** `cd log-writer && mvn -q test-compile exec:java@bench -Dbench.n=20000 -Dbench.lotes=1,100,500,2000 -Dbench.repeticoes=5 -Dbench.saida=../validacao/evidencias/bench-oficial.txt` — esses números vão para o relatório; na minha máquina o ClickHouse estourava memória com essa carga, na sua tem que completar.
> 4. **Latência do CDC (Solução 2):** fazer UPDATEs na PROC (seção 5 do `validacao/02_oracle_origem.sql`), cronometrar do COMMIT até aparecer no ClickHouse (`validacao/03_consultas_cdc.sql`). Repetir 3 vezes, anotar as três.
> 5. **Resiliência:** `docker stop projudi-connect` → 50 UPDATEs commitados → `docker start projudi-connect` → conferir que os 50 chegaram. (Obs.: tem 5 UPDATEs meus com VALOR 111–555 já commitados esperando o pipeline religar — devem chegar sozinhos, é a primeira coisa a conferir.)
> 6. Escrever o `docs/extensao-cdc.md`: passo a passo para adicionar qualquer outra tabela ao pipeline — entregável central do TCC.
> 7. Salvar TODAS as saídas em `validacao/evidencias/` e registrar decisões novas em `docs/decisoes.md`.
> 8. **Cuidado operacional descoberto na marra:** o ARCHIVELOG do Oracle acumula logs sem limpeza automática — foi provavelmente o que travou meu PC (disco 100%). Se o disco crescer, é isso; vale criar um `make limpar-archivelog` e registrar como decisão.

---

# PARTE 4 — Como apresentar à banca

## A narrativa em cinco atos (20 min de apresentação)

**Ato 1 — O problema (3 min).** A tabela LOG: cresce para sempre, mora no banco mais caro do tribunal, compete com a operação, e registra num formato que não responde às perguntas da auditoria. Mostrar um payload real `[campo:valor;...]` no slide — o formato fala por si.

**Ato 2 — As duas soluções (4 min).** O diagrama das duas frentes sobre o mesmo destino. Frase-chave: *"uma registra a intenção do usuário, a outra o efeito no banco — auditoria completa exige as duas."* Enfatizar: Solução 2 não muda uma linha do Projudi; Solução 1 muda ~14 linhas com interruptor de volta.

**Ato 3 — A demonstração ao vivo (5 min). O clímax.** Com o ambiente de pé: fazer um UPDATE num processo no Oracle, esperar ~30 segundos conversando com a banca, rodar a consulta no ClickHouse e mostrar o evento que chegou sozinho, com antes/depois estruturado. Ter o print de backup caso a demo falhe (a sua evidência do teste 4b).

**Ato 4 — Os números (4 min).** Benchmark Oracle × ClickHouse (as duas pontas: lote=1 síncrono e lote=2000), latência do CDC, resiliência (50 mudanças com o pipeline desligado, zero perdas). Sempre com as ressalvas metodológicas que o próprio relatório imprime — declarar limitações ANTES de perguntarem.

**Ato 5 — O caminho para produção (3 min).** O interruptor de três posições e o modo sombra: liga nos dois em homologação, compara registro a registro por semanas, só corta com evidência — e a volta ao Oracle está sempre disponível. Encerrar com as contribuições: solução implantável + repositório 100% reproduzível + registro metodológico de 8 suposições que só caíram em execução.

## Ênfases por público

**Para os funcionários do TJ:** custo (tirar 15 anos de log do storage Oracle), segurança da transição (sombra + rollback instantâneo), zero parada do sistema, e o bônus da Solução 2: observabilidade de mudanças sem depender de desenvolvedor lembrar de logar.

**Para os professores da UFG:** metodologia experimental — grupo de controle no mesmo host (comparar LAN×internet mediria rede, não banco), ambiente de referência único para os números, decisões registradas com erros mantidos (não apagados), e o achado transversal: **verificação estática completa não substituiu execução** — 8 casos documentados, incluindo um que passou por 30 checagens verdes e destruiria o banco na primeira recriação de container.

## As perguntas prováveis (ensaie estas respostas)

1. *"Por que não particionar/arquivar dentro do próprio Oracle?"* → Continua no storage caro, continua competindo com a operação, e não resolve o formato inauditável. O particionamento anual, aliás, JÁ existe (LOG_2010…LOG_2025) — é o paliativo atual, e não bastou.
2. *"Esses números de laboratório valem para produção?"* → São comparativos entre alternativas no mesmo ambiente controlado, não previsões absolutas. Por isso o plano de produção começa em modo sombra, medindo no ambiente real antes de qualquer corte.
3. *"E se o ClickHouse cair?"* → Solução 1: fallback automático para o Oracle, zero perda por indisponibilidade. Solução 2: o Kafka guarda posição; provamos com o pipeline desligado que nada se perde.
4. *"E se a aplicação cair com registros na fila?"* → Janela de perda declarada: até 10.000 registros ou 500 ms (parâmetros da fila). Trade-off consciente e documentado versus o desacoplamento ganho; mitigação futura apontada (gravar pós-commit).
5. *"Log de auditoria pode sair do banco transacional? E a validade legal?"* → O modo sombra existe exatamente para essa transição ser auditável: períodos de dupla escrita com comparação registro a registro antes de qualquer corte. E o MVP não desliga o Oracle — propõe o caminho.

---

# PARTE 5 — Seção técnica (para quando precisar dos detalhes)

## Arquitetura em uma linha por componente

- **Oracle (gvenzl/oracle-free em container):** réplica em miniatura da produção — schema PROJUDI com LOG (13 colunas), LOG_TIPO e PROC (43 colunas reais). Preparado para CDC: ARCHIVELOG, FORCE LOGGING, supplemental logging (ALL) COLUMNS na PROC, usuário c##dbzuser. Papel duplo: origem do CDC e **grupo de controle** do benchmark.
- **ClickHouse (single-node):** destino único. Dois bancos: `projudi_logs` (Solução 1 — `log_raw` espelha a LOG, ORDER BY (HORA, ID_USU, ID_LOG), partição mensal, ZSTD nos CLOBs) e `projudi_historico` (Solução 2 — `proc_cdc` append-only com 47 colunas: 43 da PROC + op/ts_ms/scn/ingestão).
- **Debezium (no Kafka Connect):** lê o redo log do Oracle via LogMiner e publica cada mudança como evento JSON no Kafka. Snapshot inicial + streaming contínuo.
- **Kafka:** o correio entre Debezium e ClickHouse; guarda posição — se o consumidor cair, nada se perde.
- **Kafka engine + MATERIALIZED VIEW (no ClickHouse):** consomem o tópico e desmembram o envelope Debezium nas 47 colunas — sem consumidor Java, é o próprio ClickHouse que consome.
- **log-writer (biblioteca Java 8):** o cliente da Solução 1. Fila em memória com flush por tamanho/tempo, fallback Oracle, IDs Snowflake gerados no cliente (64 bits: timestamp + workerId + sequência), interruptor ORACLE|CLICKHOUSE|AMBOS.

## As cinco decisões que você precisa saber defender

1. **ClickHouse (e não Iceberg/data lake):** requisito era single-node, SQL, schemas e migração futura do histórico — object storage + catálogo + Spark seria complexidade sem necessidade. ClickHouse ainda traz o Kafka engine (dispensa consumidor) e o Oracle table engine (migração futura por SQL).
2. **Lote + fallback (e não síncrono):** síncrono tornaria o ClickHouse dependência de disponibilidade do Projudi inteiro. A fila desacopla; o fallback garante zero perda por indisponibilidade; a janela de perda em crash de JVM é declarada em números — honestidade > promessa.
3. **ID Snowflake no cliente (e não sequence/UUID):** zero round-trip ao banco, monotônico (preserva o ORDER BY), cabe em 64 bits, faixa disjunta do histórico (sem colisão na migração), e habilita a checagem de completude por COUNT(DISTINCT ID_LOG). A sequence de produção (LOG_ID_LOG_SEQ) existe, mas usá-la reintroduziria o acoplamento que queremos remover.
4. **Interruptor de 3 estados com modo AMBOS:** o modo sombra é a evidência que justifica o corte e é o plano de rollback. No AMBOS, Oracle é a verdade e ClickHouse o candidato: falha do Oracle propaga (semântica atual), falha do ClickHouse só conta divergência.
5. **JSONAsString na Kafka engine (e não JSONEachRow):** com tipagem rígida, uma mensagem fora do formato descartaria o bloco inteiro avançando o offset — perda silenciosa, inaceitável em auditoria. Como string, a MV parseia e nada se perde sem rastro.

## O ativo metodológico: 8 suposições que só caíram em execução

1. `ENABLE_ARCHIVELOG` seria lida pela imagem Oracle — não é lida.
2. `healthy` do Docker = pronto para consulta — não é; há janela de metadados.
3. Silenciar saída de comando é inócuo — escondeu um prompt e travou o script 15 min.
4. Datafile relativo = absoluto — não é; o banco morreu na primeira recriação de container (após passar 30 checagens).
5. "Não existe sequence do LOG" — existia, com outro nome (LOG_ID_LOG_SEQ); o MAX+1 visível no código é ramo de bootstrap inalcançável (é preciso ler a guarda, não só o código).
6. O bug do `close()` preso pelo intervalo de flush — pego por teste, invisível na revisão.
7. Formato printf inválido no harness — só apareceu executando.
8. ARCHIVELOG sem limpeza enche o disco e derruba o host — descoberto quando o SEU PC travou.

A tese transversal: *verificação estática completa não substitui execução* — e o projeto tem os registros para prová-la.

## Glossário de bolso

- **CDC (Change Data Capture):** técnica de capturar mudanças direto do log interno do banco, sem tocar na aplicação.
- **LogMiner:** o mecanismo do Oracle que o Debezium usa para ler o redo log.
- **ARCHIVELOG:** modo do Oracle que preserva os logs de transação — pré-requisito do LogMiner.
- **Supplemental logging:** faz o Oracle gravar a linha completa no redo (não só o que mudou) — o CDC precisa disso para montar o antes/depois.
- **MergeTree / MATERIALIZED VIEW / Kafka engine:** peças do ClickHouse — a tabela de armazenamento, a transformação automática, e o consumidor embutido de Kafka.
- **Snapshot vs streaming:** a foto inicial das tabelas (op='r') vs a captura contínua das mudanças (op=c/u/d).
- **Snowflake ID:** identificador de 64 bits gerado localmente: timestamp + identificador da instância + sequência.
- **Modo sombra:** gravar nos dois destinos ao mesmo tempo para comparar antes de confiar no novo.
