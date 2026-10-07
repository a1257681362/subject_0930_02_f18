package com.evops.aqua.noculture.service;

import com.evops.aqua.entity.Pond;
import com.evops.aqua.noculture.entity.NoCultureRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 禁养空间匹配：判断池塘中心点是否落在禁养范围内。
 * AREA 整海区规则——池塘 seaArea 与规则 seaArea 归一化（去首尾空白）后相等即命中；
 * POLYGON 多边形规则——按池塘经纬度做点-多边形（射线法）判定，
 * 池塘缺经纬度视为不命中（不能据此冻结，避免误伤无坐标池塘）。
 * 顶点取自规则 polygon_json：[[lon,lat],[lon,lat],...]，无需显式闭合。
 * 约定：边界相切计入命中——点落在多边形任一边或顶点上视为在禁养范围内，
 * 与边朝向无关（射线法本身对边界点判定随边朝向不定，必须先做在边判定）。
 */
@Component
public class NoCultureSpatialMatcher {

    private final ObjectMapper objectMapper;

    public NoCultureSpatialMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean hits(NoCultureRule rule, Pond pond) {
        if (pond == null) {
            return false;
        }
        if (NoCultureRule.SCOPE_AREA.equals(rule.getScopeType())) {
            return normalize(rule.getSeaArea()) != null
                    && normalize(rule.getSeaArea()).equals(normalize(pond.getSeaArea()));
        }
        if (NoCultureRule.SCOPE_POLYGON.equals(rule.getScopeType())) {
            BigDecimal lon = pond.getLongitude();
            BigDecimal lat = pond.getLatitude();
            if (lon == null || lat == null) {
                return false;
            }
            double[][] ring = parseRing(rule.getPolygonJson());
            return ring != null && contains(ring, lon.doubleValue(), lat.doubleValue());
        }
        return false;
    }

    /**
     * 射线法点-多边形，顶点按顺序连成闭合环。
     * 约定：边界相切计入命中——点落在任一边或顶点上即返回 true（先做在边判定，
     * 再对严格外部点做射线奇偶判定），保证同一边界点与边朝向无关、判定确定。
     */
    public boolean contains(double[][] ring, double x, double y) {
        if (ring == null || ring.length < 3) {
            return false;
        }
        int n = ring.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if (onSegment(ring[j][0], ring[j][1], ring[i][0], ring[i][1], x, y)) {
                return true;
            }
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring[i][0], yi = ring[i][1];
            double xj = ring[j][0], yj = ring[j][1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 点是否在线段上（含端点）：叉积判共线 + 包围盒判落段，容差 1e-9 度（约0.1毫米）。 */
    private boolean onSegment(double x1, double y1, double x2, double y2, double x, double y) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double cross = (x - x1) * dy - (y - y1) * dx;
        double len = Math.hypot(dx, dy);
        double eps = 1e-9;
        if (len < eps) {
            // 退化顶点（重复点）：按点重合判定
            return Math.hypot(x - x1, y - y1) <= eps;
        }
        if (Math.abs(cross) / len > eps) {
            return false;
        }
        return x >= Math.min(x1, x2) - eps && x <= Math.max(x1, x2) + eps
                && y >= Math.min(y1, y2) - eps && y <= Math.max(y1, y2) + eps;
    }

    /** 海区名归一化：去首尾空白；空白串视为 null（不参与命中）。 */
    private String normalize(String seaArea) {
        if (seaArea == null) {
            return null;
        }
        String trimmed = seaArea.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 解析 [[lon,lat],...]；非法/不足3点返回 null（登记时已强校验，此处防御）。 */
    public double[][] parseRing(String polygonJson) {
        if (polygonJson == null || polygonJson.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode arr = objectMapper.readTree(polygonJson);
            if (!arr.isArray() || arr.size() < 3) {
                return null;
            }
            double[][] ring = new double[arr.size()][2];
            for (int i = 0; i < arr.size(); i++) {
                JsonNode p = arr.get(i);
                if (!p.isArray() || p.size() < 2) {
                    return null;
                }
                ring[i][0] = p.get(0).asDouble();
                ring[i][1] = p.get(1).asDouble();
            }
            return ring;
        } catch (Exception ex) {
            return null;
        }
    }
}
