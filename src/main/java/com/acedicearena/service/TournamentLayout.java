package com.acedicearena.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 锦标赛结构的共享常量与定位逻辑：每队 6 个小队 × 5 人，
 * 场次 id 前缀 g/s/f 分别对应 1/4 决赛、半决赛、决赛/加赛（bracket 轮次 1/2/3）。
 */
final class TournamentLayout {
    static final int SQUAD_COUNT = 6;
    static final int SQUAD_SIZE = 5;

    private TournamentLayout() {
    }

    static ObjectNode findTeam(ObjectNode root, String teamId) {
        for (JsonNode candidate : root.path("teams"))
            if (teamId.equals(candidate.path("id").asText())) return (ObjectNode) candidate;
        throw new IllegalStateException("队伍资料不存在");
    }

    /** 成员所在小队下标（0 起）；查不到（手工构造状态）按 0 号小队兜底。 */
    static int squadIndexOf(ObjectNode team, String playerId) {
        JsonNode squads = team.path("squads");
        if (squads.isArray()) {
            for (int k = 0; k < squads.size(); k++)
                for (JsonNode id : squads.get(k))
                    if (playerId.equals(id.asText())) return k;
        }
        return 0;
    }

    /** 场次 id 前缀 → bracket 轮次；未知前缀直接拒绝，不静默兜底。 */
    static int bracketRoundOf(String matchId) {
        if (matchId.startsWith("g")) return 1;
        if (matchId.startsWith("s")) return 2;
        if (matchId.startsWith("f")) return 3;
        throw new IllegalArgumentException("未知场次编号: " + matchId);
    }
}
