package br.com.cardapiofc;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Regras puras de comparação entre o rótulo de canal mostrado no app e os nomes
 * da lista IPTV do usuário. Sem dependências do Android para permitir testes
 * fora do aparelho.
 *
 * <p>O padrão das listas é sempre o mesmo: um nome base com variações de
 * numeração e sufixo (SPORTV 2, CAZÉ TV 1..10, PRIME VIDEO 9 HD,
 * PARAMOUNT - FUTEBOL, HBO MAX...). O rótulo do app vem sem essas variações
 * ("CazéTV", "Prime Video", "Max"), então a comparação precisa tolerar
 * espaçamento, número de subcanal e sufixos descritivos.
 */
final class ChannelMatcher {
    private ChannelMatcher() {}

    static boolean matches(String displayName, String playlistName) {
        return matchesNormalized(normalizeChannelName(displayName), normalizeChannelName(playlistName));
    }

    static boolean matchesNormalized(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        // Famílias com regra própria decidem primeiro; se a regra não aceitar,
        // a comparação genérica continua valendo para todas as listas.
        if (isNamedChannel(a, "PREMIERE") && isNamedChannel(b, "PREMIERE") && matchesPremiere(a, b)) return true;
        if (isNamedChannel(a, "PARAMOUNT") && isNamedChannel(b, "PARAMOUNT") && matchesParamount(a, b)) return true;
        // Globo mantém a regra de região exclusiva (nunca cai na genérica).
        if (isNamedChannel(a, "GLOBO") && isNamedChannel(b, "GLOBO")) return matchesGlobo(a, b);
        return matchesLoose(a, b);
    }

    /** Comparação genérica que vale para qualquer canal de qualquer lista. */
    private static boolean matchesLoose(String a, String b) {
        // Canais fixo da partida (FLAMENGO X GRÊMIO) só entram pelas
        // sugestões da partida, nunca pelo rótulo de um canal transmissor.
        if (isFixtureChannelName(b)) return false;
        String compactA = compact(a);
        String compactB = compact(b);
        // Diferença só de espaçamento: "CAZETV" x "CAZE TV".
        if (compactA.equals(compactB)) return true;
        if (compactA.length() < 3) return false;
        // Rótulo sem número x lista numerada: "CAZETV" x "CAZE TV 1",
        // "PRIMEVIDEO" x "PRIME VIDEO 9" (e o inverso, lista sem número).
        if (compactB.startsWith(compactA) && isNumeric(compactB.substring(compactA.length()))) return true;
        if (compactA.startsWith(compactB) && isNumeric(compactA.substring(compactB.length()))) return true;
        // Sufixo tolerado na lista: todos os termos do rótulo precisam estar
        // no nome do canal ("PARAMOUNT" x "PARAMOUNT FUTEBOL", "MAX" x "HBO MAX").
        for (String token : a.split(" ")) {
            if (!(" " + b + " ").contains(" " + token + " ")) return false;
        }
        return true;
    }

    private static String compact(String value) {
        return value.replace(" ", "");
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') return false;
        }
        return true;
    }

    private static boolean isNamedChannel(String value, String name) {
        return value.equals(name) || value.startsWith(name + " ");
    }

    private static String stripBroadcastPrefix(String value) {
        if (value.startsWith("REDE ")) return value.substring("REDE ".length()).trim();
        if (value.startsWith("TV ")) return value.substring("TV ".length()).trim();
        return value;
    }

    static String normalizeChannelName(String value) {
        return stripBroadcastPrefix(canonical(value));
    }

    static String canonical(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ").trim().replaceAll("\\s+", " ");
        return normalized.replaceAll("\\b(FULL HD|FHD|ULTRA HD|UHD|4K|8K|HD|SD|1080P|720P|H264|H265|HEVC)\\b", " ")
                .trim().replaceAll("\\s+", " ");
    }

    static boolean isFixtureChannelName(String name) {
        String padded = " " + name + " ";
        return hasToken(padded, "X") || hasToken(padded, "VS") || hasToken(padded, "VERSUS");
    }

    static String normalizeTeamName(String value) {
        String normalized = normalizeChannelName(value);
        return normalized.length() < 3 ? "" : normalized;
    }

    static int fixtureScore(String channelName, String home, String away) {
        if (!isFixtureChannelName(channelName)) return 0;
        boolean homeMatch = matchesTeamPhrase(channelName, home);
        boolean awayMatch = matchesTeamPhrase(channelName, away);
        return (homeMatch ? 1 : 0) + (awayMatch ? 1 : 0);
    }

    private static boolean matchesTeamPhrase(String channelName, String team) {
        if (team.isEmpty()) return false;
        String paddedName = " " + channelName + " ";
        if (paddedName.contains(" " + team + " ")) return true;
        for (String word : team.split(" ")) {
            if (word.length() >= 5 && paddedName.contains(" " + word + " ")) return true;
        }
        return false;
    }

    private static boolean matchesPremiere(String requested, String available) {
        String requestedVariant = requested.substring("PREMIERE".length()).trim();
        String availableVariant = available.substring("PREMIERE".length()).trim();
        if (isPremiereFc(requestedVariant)) {
            return isPremiereFc(availableVariant) || "1".equals(availableVariant)
                    || "CLUBE".equals(availableVariant) || "CLUBES".equals(availableVariant);
        }
        if (requestedVariant.isEmpty()) return availableVariant.isEmpty() || "1".equals(availableVariant);
        if (requestedVariant.matches("\\d+")) return requestedVariant.equals(availableVariant);
        if ("CLUBE".equals(requestedVariant) || "CLUBES".equals(requestedVariant)) {
            return "CLUBE".equals(availableVariant) || "CLUBES".equals(availableVariant);
        }
        return requestedVariant.equals(availableVariant);
    }

    private static boolean isPremiereFc(String variant) {
        return "FC".equals(variant) || "F C".equals(variant)
                || "FUTEBOL CLUBE".equals(variant) || "FOOTBALL CLUB".equals(variant);
    }

    private static boolean matchesParamount(String requested, String available) {
        String requestedVariant = requested.substring("PARAMOUNT".length()).trim();
        String availableVariant = available.substring("PARAMOUNT".length()).trim();
        if (requestedVariant.isEmpty()) return availableVariant.isEmpty() || availableVariant.matches("\\d+");
        if (requestedVariant.matches("\\d+")) return requestedVariant.equals(availableVariant);
        return requestedVariant.equals(availableVariant);
    }

    private static boolean matchesGlobo(String requested, String available) {
        String requestedRegion = globoRegion(requested);
        String availableRegion = globoRegion(available);
        if (requestedRegion != null) return requestedRegion.equals(availableRegion);
        return "SP".equals(availableRegion) || "MG".equals(availableRegion) || "RJ".equals(availableRegion);
    }

    private static String globoRegion(String value) {
        String padded = " " + value + " ";
        if (hasToken(padded, "SP") || value.contains("SAO PAULO")) return "SP";
        if (hasToken(padded, "MG") || hasToken(padded, "MINAS") || value.contains("MINAS GERAIS")
                || hasToken(padded, "BH") || value.contains("BELO HORIZONTE")) return "MG";
        if (hasToken(padded, "RJ") || value.contains("RIO DE JANEIRO")) return "RJ";
        if (hasToken(padded, "DF") || value.contains("DISTRITO FEDERAL") || value.contains("BRASILIA")) return "DF";
        if (hasToken(padded, "RS") || value.contains("RIO GRANDE DO SUL")) return "RS";
        if (hasToken(padded, "PR") || value.contains("PARANA")) return "PR";
        if (hasToken(padded, "SC") || value.contains("SANTA CATARINA")) return "SC";
        if (hasToken(padded, "BA") || value.contains("BAHIA")) return "BA";
        if (hasToken(padded, "PE") || value.contains("PERNAMBUCO")) return "PE";
        if (hasToken(padded, "CE") || value.contains("CEARA")) return "CE";
        if (hasToken(padded, "GO") || value.contains("GOIAS")) return "GO";
        if (hasToken(padded, "ES") || value.contains("ESPIRITO SANTO")) return "ES";
        if (hasToken(padded, "AM") || value.contains("AMAZONAS")) return "AM";
        if (hasToken(padded, "PA") || value.contains("PARA")) return "PA";
        if (hasToken(padded, "MA") || value.contains("MARANHAO")) return "MA";
        if (hasToken(padded, "MS") || value.contains("MATO GROSSO DO SUL")) return "MS";
        if (hasToken(padded, "MT") || value.contains("MATO GROSSO")) return "MT";
        if (hasToken(padded, "RN") || value.contains("RIO GRANDE DO NORTE")) return "RN";
        if (hasToken(padded, "PB") || value.contains("PARAIBA")) return "PB";
        if (hasToken(padded, "AL") || value.contains("ALAGOAS")) return "AL";
        if (hasToken(padded, "SE") || value.contains("SERGIPE")) return "SE";
        if (hasToken(padded, "PI") || value.contains("PIAUI")) return "PI";
        if (hasToken(padded, "TO") || value.contains("TOCANTINS")) return "TO";
        if (hasToken(padded, "RO") || value.contains("RONDONIA")) return "RO";
        if (hasToken(padded, "AC") || value.contains("ACRE")) return "AC";
        if (hasToken(padded, "AP") || value.contains("AMAPA")) return "AP";
        if (hasToken(padded, "RR") || value.contains("RORAIMA")) return "RR";
        if (hasToken(padded, "NE") || value.contains("NORDESTE")) return "NE";
        return null;
    }

    private static boolean hasToken(String padded, String token) {
        return padded.contains(" " + token + " ");
    }
}
