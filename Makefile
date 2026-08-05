# =============================================================================
# projudi-log-offload — atalhos do ambiente local
#
# Todos os alvos rodam a partir da RAIZ do repositório e apontam o compose para
# infra/docker-compose.yml com o .env da raiz.
#
# `make help` lista os alvos.
# =============================================================================

SHELL := /bin/bash

COMPOSE_FILE := infra/docker-compose.yml
DC           := docker compose --env-file .env -f $(COMPOSE_FILE)

# Serviço alvo de `make logs` (padrão: todos)
s ?=

# Argumentos extras de `make limpar-archivelog` (ex.: a="--tudo", a="--horas 6")
a ?=

.DEFAULT_GOAL := help
.PHONY: help setup up up-lite down restart reset logs status ps validate validate-lite archivelog limpar-archivelog disco ch sql connector connector-status build

help: ## Lista os alvos disponíveis
	@echo ""
	@echo "projudi-log-offload — ambiente local"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-18s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Exemplos:"
	@echo "    make logs s=oracle              segue o log de um serviço"
	@echo "    make logs                       segue o log de todos"
	@echo "    make limpar-archivelog a=--tudo apaga TODOS os archived logs"
	@echo ""

setup: ## Verifica pré-requisitos, cria o .env e baixa/constrói as imagens
	@./scripts/setup.sh

.env:
	@echo ".env não existe — rodando o setup"
	@./scripts/setup.sh

up: .env ## Sobe todos os serviços e espera ficarem saudáveis
	@echo "Subindo o ambiente (o Oracle leva de 2 a 5 min no primeiro start)..."
	@$(DC) up -d --wait --wait-timeout 600 || { \
		echo ""; \
		echo "Algum serviço não ficou saudável no tempo limite."; \
		echo "Veja o estado com 'make status' e os logs com 'make logs s=<serviço>'."; \
		exit 1; \
	}
	@echo ""
	@$(MAKE) --no-print-directory status
	@echo ""
	@echo "Pronto. Rode 'make validate' para conferir o ambiente."

up-lite: .env ## Sobe só o ClickHouse (para máquinas com pouca RAM)
	@echo "Subindo apenas o ClickHouse (o Kafka vem junto, é dependência da tabela Kafka engine)..."
	@$(DC) up -d --wait --wait-timeout 300 clickhouse
	@echo ""
	@$(MAKE) --no-print-directory status
	@echo ""
	@echo "Modo reduzido: ~3 GB de RAM. Serve para iterar o log-writer (Frente B)."
	@echo "NÃO serve para o benchmark — sem o Oracle local não há grupo de controle."
	@echo "Ver docs/ambientes.md, seção 3."

down: ## Para os serviços, preservando os dados
	@$(DC) down

restart: ## Reinicia os serviços sem apagar os dados
	@$(DC) restart

reset: ## APAGA TUDO (containers + volumes) e sobe do zero
	@echo ""
	@echo "  Isto remove os volumes: dados do Oracle, do ClickHouse e do Kafka."
	@echo "  Os DDLs e o seed serão reaplicados no próximo 'make up'."
	@echo ""
	@read -p "  Digite 'sim' para confirmar: " resposta; \
	if [ "$$resposta" = "sim" ]; then \
		$(DC) down -v; \
		echo ""; \
		echo "Ambiente removido. Rode 'make up' para recriar do zero."; \
	else \
		echo "Cancelado."; \
	fi

logs: ## Segue os logs (use s=<serviço> para filtrar)
	@$(DC) logs -f --tail=200 $(s)

status: ## Mostra o estado e a saúde dos containers
	@$(DC) ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"

ps: status ## Alias de status

validate: ## Roda a bateria de validação do ambiente
	@./scripts/validate.sh

validate-lite: ## Valida só o ClickHouse (para quem subiu com up-lite)
	@./scripts/validate.sh --lite

archivelog: ## Coloca o Oracle em ARCHIVELOG (pré-requisito do CDC)
	@./scripts/enable-archivelog.sh

limpar-archivelog: ## Apaga archived redo logs (a=--tudo apaga todos; pode quebrar o CDC)
	@./scripts/limpar-archivelog.sh $(a)

disco: ## Mostra uso da FRA, dos volumes Docker e do disco do host
	@./scripts/disco.sh

build: ## Reconstrói a imagem do Kafka Connect (com o driver ojdbc)
	@$(DC) build --no-cache connect

ch: ## Abre um clickhouse-client interativo
	@$(DC) exec clickhouse clickhouse-client

sql: ## Abre um sqlplus interativo no PDB, como PROJUDI
	@set -a; . ./.env; set +a; \
	$(DC) exec oracle sqlplus "PROJUDI/$$ORACLE_PROJUDI_PASSWORD@//localhost:1521/FREEPDB1"

connector: ## Registra (ou recria) o conector Debezium da PROJUDI.PROC
	@./scripts/register-connector.sh

connector-status: ## Mostra o estado do conector Debezium
	@./scripts/register-connector.sh --status
