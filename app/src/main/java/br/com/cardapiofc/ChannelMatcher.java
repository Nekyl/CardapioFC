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
 * PARAMOUNT - FUTEBOL, HBO MAX...). O número de subcanal é exato: rótulo sem
 * número equivale ao canal 1 (ou sem número) e rótulo com número só casa com
 * esse mesmo número. Espaçamento e sufixos descritivos são tolerados.
 */
final class ChannelMatcher {
    private ChannelMatcher() {}

    static boolean matches(String displayName, String playlistName) {
        return matchesNormalized(normalizeChannelName(displayName), normalizeChannelName(playlistName));
    }

    static boolean matchesNormalized(String a, String b) {
        return matchesNormalized(a, b, false);
    }

    /**
     * @param lenient segunda passada, usada quando a regra estrita não achou nada na lista:
     *                aceita o CazéTV sem numeral romano (listas que só têm "CAZE TV FHD").
     */
    static boolean matchesNormalized(String a, String b, boolean lenient) {
        if (a.isEmpty() || b.isEmpty()) return false;
        // CazéTV: preferência pela variante de esporte com numeral romano (CAZÉ TV HD I..VIII).
        // Só se a lista não tiver nenhuma (segunda passada), vale o número exato (sem número = 1).
        if (isCaze(a) && isCaze(b)) return matchesCaze(a, b, lenient);
        if (a.equals(b)) return true;
        // Famílias com regra própria decidem primeiro; se a regra não aceitar,
        // a comparação genérica continua valendo para todas as listas.
        if (isNamedChannel(a, "PREMIERE") && isNamedChannel(b, "PREMIERE") && matchesPremiere(a, b)) return true;
        // Paramount+ (esportes) nunca casa com o "Paramount Channel" (canal de filmes).
        if (isNamedChannel(a, "PARAMOUNT") && isNamedChannel(b, "PARAMOUNT")) return matchesParamount(a, b);
        // Globo mantém a regra de região exclusiva (nunca cai na genérica).
        if (isNamedChannel(a, "GLOBO") && isNamedChannel(b, "GLOBO")) return matchesGlobo(a, b);
        return matchesLoose(a, b);
    }

    /**
     * Comparação genérica que vale para qualquer canal de qualquer lista.
     *
     * <p>Regra do número de variante: o rótulo sem número equivale ao canal 1
     * (ou ao canal da lista sem número); um rótulo com número só casa com o
     * mesmo número. Ex.: "PRIME VIDEO" casa com "PRIME VIDEO 1 HD" e com
     * "PRIME VIDEO", mas nunca com "PRIME VIDEO 2 HD"; "ESPN 2" casa só com
     * "ESPN 2". O restante do nome (sem o número) tolera espaçamento e sufixos
     * descritivos.
     */
    private static boolean matchesLoose(String a, String b) {
        // Canais fixo da partida (FLAMENGO X GRÊMIO) só entram pelas
        // sugestões da partida, nunca pelo rótulo de um canal transmissor.
        if (isFixtureChannelName(b)) return false;
        if (compact(a).equals(compact(b))) return true;
        Variant variantA = splitVariant(a);
        Variant variantB = splitVariant(b);
        if (variantA.number != variantB.number) return false;
        String baseA = variantA.base;
        String baseB = variantB.base;
        if (baseA.isEmpty() || baseB.isEmpty()) return false;
        // Rótulos cujo nome muda de uma lista para outra (apelidos conhecidos).
        Boolean alias = matchesAlias(baseA, baseB);
        if (alias != null) return alias;
        String compactA = compact(baseA);
        // Diferença só de espaçamento: "CAZETV" x "CAZE TV".
        if (compactA.equals(compact(baseB))) return true;
        if (compactA.length() < 3) return false;
        // Sufixo tolerado na lista: todos os termos do rótulo precisam estar
        // no nome do canal ("PARAMOUNT" x "PARAMOUNT FUTEBOL", "MAX" x "HBO MAX").
        for (String token : baseA.split(" ")) {
            if (!(" " + baseB + " ").contains(" " + token + " ")) return false;
        }
        return true;
    }

    /**
     * Apelidos de canais que as listas nomeiam de formas diferentes do rótulo
     * do app. Devolve null quando o rótulo não tem apelido (segue a regra
     * genérica). O número da variante já foi comparado antes.
     */
    private static Boolean matchesAlias(String baseA, String baseB) {
        String padded = " " + baseB + " ";
        // "GE TV" x "GLOBO GE TV", "GLOBO GE TVL", "GLOBO GE FUTEBOL".
        if (baseA.equals("GE TV") || baseA.equals("GE")) {
            if (!padded.contains(" GE ")) return compact(baseA).equals(compact(baseB));
            return padded.contains(" TV ") || padded.contains(" TVL ") || padded.contains(" FUTEBOL ")
                    || baseB.equals("GE") || baseB.equals("GLOBO GE");
        }
        // "PRIME VIDEO" é só o da Amazon ("PRIME VIDEO AMAZON 3", "AMAZON PRIME VIDEO",
        // "PRIME VIDEO 2"). Outros canais com "Prime" (PRIME BOX BRAZIL, PRIME VIDEO
        // FILMES...) têm nomes com palavras a mais e ficam de fora.
        if (baseA.equals("PRIME VIDEO") || baseA.equals("PRIME")) {
            boolean prime = false, video = false, amazon = false, extra = false;
            for (String token : baseB.split(" ")) {
                switch (token) {
                    case "PRIME": prime = true; break;
                    case "VIDEO": case "VIDEOS": video = true; break;
                    case "PRIMEVIDEO": prime = true; video = true; break;
                    case "AMAZON": amazon = true; break;
                    case "BR": case "BRASIL": case "BRAZIL": break;
                    default: extra = true;
                }
            }
            // Palavras a mais só são aceitas no "Prime Video Amazon" (ex.: "... AMAZON BR 1");
            // sem "Amazon" qualquer palavra a mais indica outro canal (PRIME BOX, FILMES...).
            if (extra && !(amazon && video)) return false;
            return prime && (video || amazon);
        }
        return null;
    }

    /** Nome sem o número de variante, o número (1 quando não há) e se o número veio escrito. */
    private static final class Variant {
        final String base;
        final int number;
        final boolean explicit;
        final boolean roman;
        Variant(String base, int number, boolean explicit, boolean roman) { this.base = base; this.number = number; this.explicit = explicit; this.roman = roman; }
    }

    private static int romanValue(String token) {
        switch (token) {
            case "I": return 1; case "II": return 2; case "III": return 3; case "IV": return 4;
            case "V": return 5; case "VI": return 6; case "VII": return 7; case "VIII": return 8;
            case "IX": return 9;
            default: return 0;
        }
    }

    private static Variant splitVariant(String name) {
        String[] tokens = name.split(" ");
        int numberIndex = -1;
        int number = 1;
        boolean roman = false;
        // Numeral romano no último termo ("CAZE TV V" = 5), nunca como nome inteiro.
        if (tokens.length > 1 && romanValue(tokens[tokens.length - 1]) > 0) {
            numberIndex = tokens.length - 1;
            number = romanValue(tokens[numberIndex]);
            roman = true;
        } else {
            for (int index = tokens.length - 1; index >= 0; index--) {
                String token = tokens[index];
                if (isNumeric(token) && token.length() <= 2) {
                    numberIndex = index;
                    number = Integer.parseInt(token);
                    break;
                }
            }
        }
        // Número colado no nome ("SPORTV2", "CAZETV10"), só no último termo.
        if (numberIndex < 0 && tokens.length > 0) {
            String last = tokens[tokens.length - 1];
            java.util.regex.Matcher glued = java.util.regex.Pattern.compile("^([A-Z]{3,})(\\d{1,2})$").matcher(last);
            if (glued.matches()) {
                tokens[tokens.length - 1] = glued.group(1);
                return new Variant(String.join(" ", tokens).trim(), Integer.parseInt(glued.group(2)), true, false);
            }
        }
        StringBuilder base = new StringBuilder();
        for (int index = 0; index < tokens.length; index++) {
            if (index == numberIndex) continue;
            if (base.length() > 0) base.append(' ');
            base.append(tokens[index]);
        }
        return new Variant(base.toString(), number, numberIndex >= 0, roman);
    }

    private static boolean isCaze(String value) {
        return compact(splitVariant(value).base).equals("CAZETV");
    }

    private static boolean matchesCaze(String requested, String available, boolean lenient) {
        Variant want = splitVariant(requested);
        Variant have = splitVariant(available);
        if (want.number != have.number) return false;
        return lenient || have.roman;
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

    static boolean isInappropriateChannelName(String value) {
        String normalized = normalizeChannelName(value);
        for (String token : normalized.split(" ")) {
            switch (token) {
                case "ADULT": case "ADULTS": case "ADULTO": case "ADULTOS":
                case "XXX": case "PORN": case "PORNO": case "PORNOGRAFIA":
                case "SEX": case "SEXO": case "SEXY": case "EROTIC": case "EROTICO": case "EROTICA":
                case "HENTAI": case "PLAYBOY": case "ONLYFANS": case "BRAZZERS": case "REDTUBE":
                case "NUDE": case "NUDITY": case "FETISH": case "PRIVATE":
                    return true;
                default:
                    break;
            }
        }
        return false;
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
        Variant want = splitVariant(requested);
        Variant have = splitVariant(available);
        if (want.number != have.number) return false;
        String paddedHave = " " + have.base + " ";
        if (paddedHave.contains(" CHANNEL ") || paddedHave.contains(" CANAL ")) return false;
        for (String token : want.base.split(" ")) {
            if (!paddedHave.contains(" " + token + " ")) return false;
        }
        return true;
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