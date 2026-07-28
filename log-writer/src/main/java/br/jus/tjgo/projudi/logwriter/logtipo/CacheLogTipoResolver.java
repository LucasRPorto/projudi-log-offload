package br.jus.tjgo.projudi.logwriter.logtipo;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorador que consulta o delegado uma única vez por código.
 *
 * <p>Cache sem expiração e sem limite de tamanho, de propósito: a
 * {@code PROJUDI.LOG_TIPO} tem algumas dezenas de linhas (a sequence de
 * produção está em 44) e é recarregada por inteiro quando muda, não
 * incrementada. Um mapa de dezenas de entradas não precisa de política de
 * despejo, e uma entrada expirando periodicamente só reintroduziria latência no
 * caminho de escrita sem nenhum ganho.</p>
 *
 * <p><b>Consequência aceita:</b> um tipo de log novo, cadastrado enquanto a
 * aplicação está de pé, resolve para {@code 0} até o próximo restart se o
 * primeiro acesso a ele acontecer antes da carga da dimensão. Por isso o miss
 * é contado — {@link #getNaoResolvidos()} é o número que denuncia a dimensão
 * desatualizada.</p>
 */
public final class CacheLogTipoResolver implements LogTipoResolver {

    private final LogTipoResolver delegado;
    private final ConcurrentMap<Long, Long> cache = new ConcurrentHashMap<Long, Long>();
    private final AtomicLong consultas = new AtomicLong();
    private final AtomicLong naoResolvidos = new AtomicLong();

    public CacheLogTipoResolver(LogTipoResolver delegado) {
        if (delegado == null) {
            throw new IllegalArgumentException("delegado não pode ser nulo");
        }
        this.delegado = delegado;
    }

    @Override
    public long resolver(long logTipoCodigo) {
        Long chave = Long.valueOf(logTipoCodigo);
        Long emCache = cache.get(chave);
        if (emCache != null) {
            return emCache.longValue();
        }

        consultas.incrementAndGet();
        long resolvido = delegado.resolver(logTipoCodigo);
        if (resolvido == 0L) {
            // Não cacheia o miss: a dimensão pode ser recarregada sem restart, e
            // insistir num zero cacheado transformaria um atraso de carga em
            // dano permanente aos registros daquele tipo.
            naoResolvidos.incrementAndGet();
            return 0L;
        }
        cache.put(chave, Long.valueOf(resolvido));
        return resolvido;
    }

    /** Quantas vezes o delegado foi realmente chamado (misses de cache). */
    public long getConsultas() {
        return consultas.get();
    }

    /** Códigos que o delegado não soube traduzir. Deve ser zero. */
    public long getNaoResolvidos() {
        return naoResolvidos.get();
    }

    public int getTamanho() {
        return cache.size();
    }

    public void limpar() {
        cache.clear();
    }

    @Override
    public String toString() {
        return "CacheLogTipoResolver[" + cache.size() + " em cache, "
                + naoResolvidos.get() + " não resolvidos, sobre " + delegado + "]";
    }
}
