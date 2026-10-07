package com.evops.aqua.noculture.service;

import com.evops.aqua.entity.Pond;
import com.evops.aqua.noculture.entity.NoCultureRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 禁养空间匹配：判断池塘中心点是否落在禁养范围内。
 * AREA 整海区规则——池塘 seaArea 与规则 seaArea 相等即命中；
 * POLYGON 多边形规则——按池塘经纬度做点-多边形（射线法）判定，
 * 池塘缺经纬度视为不命中（不能据此冻结，避免误伤无坐标池塘）。
 * 顶点取自规则 polygon_json：[[lon,lat],[lon,lat],...]，无需显式闭合。
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
            return rule.getSeaArea() != null
                    && rule.getSeaArea().equals(pond.getSeaArea());
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

    /** 射线法点-多边形：边界点计入（比较取 <= / > 与 >= / < 组合），顶点按顺序连成闭合环。 */
    public boolean contains(double[][] ring, double x, double y) {
        if (ring == null || ring.length < 3) {
            return false;
        }
        boolean inside = false;
        int n = ring.length;
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
