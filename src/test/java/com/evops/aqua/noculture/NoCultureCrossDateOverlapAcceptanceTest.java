package com.evops.aqua.noculture;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.entity.SeedlingBatch;
import com.evops.aqua.mapper.SeedlingBatchMapper;
import com.evops.aqua.noculture.entity.NoCultureChangeEvidence;
import com.evops.aqua.noculture.entity.NoCultureFreeze;
import com.evops.aqua.noculture.entity.NoCultureMatch;
import com.evops.aqua.noculture.entity.NoCultureRule;
import com.evops.aqua.noculture.mapper.NoCultureChangeEvidenceMapper;
import com.evops.aqua.noculture.mapper.NoCultureFreezeMapper;
import com.evops.aqua.noculture.mapper.NoCultureMatchMapper;
import com.evops.aqua.noculture.mapper.NoCultureRuleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 海区禁养规则补强验收（aqua-11 增补）：覆盖 NoCultureAcceptanceTest 未覆盖的场景。
 * 主线：边界相切按约定计入命中（切点/顶点批次冻结、转池拦截，区外邻近点不误伤）
 * → 跨日期半开窗口的转池单放行/拦截 → 未来生效规则发布即生成冻结清单但不提前拦
 * → 多海区重叠（多边形跨海区 + 整海区规则）冻结、按更严格裁决、撤销挂起再放行
 * → 缺关键字段一次性指出全部缺项、补齐后同 requestId 只重试当前步骤
 * → 业务失败保留原始凭证（CREATE_RULE_FAILED / REVOKE_RULE_FAILED 可按 requestId 追溯）。
 * 标识统一 NCD-/ncd- 前缀，与 NoCultureAcceptanceTest（NC-/nc-）互不干扰，执行顺序无关。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoCultureCrossDateOverlapAcceptanceTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeedlingBatchMapper batchMapper;
    @Autowired
    private NoCultureRuleMapper ruleMapper;
    @Autowired
    private NoCultureMatchMapper matchMapper;
    @Autowired
    private NoCultureFreezeMapper freezeMapper;
    @Autowired
    private NoCultureChangeEvidenceMapper evidenceMapper;

    private final List<String> report = new ArrayList<>();
    private String sessionCookie;

    private Long pondTangent;  // 禁养C海区，中心点在多边形右边线上（相切）
    private Long pondVertex;   // 禁养C海区，中心点在多边形顶点上（相切）
    private Long pondNearOut;  // 禁养C海区，边线外0.01度（严格区外）
    private Long pondDIn;      // 禁养D海区，多边形严格内部
    private Long pondDOut;     // 禁养D海区，多边形外
    private Long pondE;        // 禁养E海区（跨日期窗口）
    private Long pondF;        // 禁养F海区（未来生效）
    private Long pondX;        // 禁养X海区（无规则，转池目标池）

    private Long batchTan;
    private Long batchVtx;
    private Long batchOut;
    private Long batchDIn;
    private Long batchDOut;
    private Long batchDDone;   // 多边形内已完成批：只提示不回写
    private Long batchE;
    private Long batchF;

    private Long r1;           // 多边形规则（归口禁养C海区，严格级2，跨C/D两海区命中）
    private Long r2;           // 禁养E海区整海区规则（有限窗口）
    private Long r3;           // 禁养F海区整海区规则（未来生效）
    private Long r4;           // 禁养D海区整海区规则（严格级5，与R1跨海区重叠）
    private Long r7;           // 缺项补齐后登记的规则（禁养G海区，无池塘）

    // 矩形多边形 [121.0,26.0]-[121.4,26.4]：横跨禁养C/禁养D两海区的池塘
    private final List<List<Double>> polygon = Arrays.asList(
            Arrays.asList(121.0, 26.0),
            Arrays.asList(121.4, 26.0),
            Arrays.asList(121.4, 26.4),
            Arrays.asList(121.0, 26.4));

    @Test
    void acceptanceCrossDateAndOverlap() {
        checkpoint("CP0", "登录认证", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("username", "bootstrap");
            body.put("password", "bootstrap");
            ResponseEntity<String> resp = call(HttpMethod.POST, "/api/auth/login", body);
            assertSuccess(json(resp), "登录");
            List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
            check(cookies != null && !cookies.isEmpty(), "登录成功但未下发会话Cookie");
            sessionCookie = cookies.get(0).split(";")[0];
        });

        checkpoint("CP1", "建池八口（相切/顶点/区外邻近/跨海区/时间/未来/目标池）并投苗七批、完成一批", () -> {
            pondTangent = createPond("NCD-TAN", "相切边线池", "禁养C海区", "121.40", "26.20");
            pondVertex = createPond("NCD-VTX", "相切顶点池", "禁养C海区", "121.40", "26.40");
            pondNearOut = createPond("NCD-OUT", "区外邻近池", "禁养C海区", "121.41", "26.20");
            pondDIn = createPond("NCD-DIN", "多边形内D区池", "禁养D海区", "121.20", "26.20");
            pondDOut = createPond("NCD-DOUT", "多边形外D区池", "禁养D海区", "122.00", "26.20");
            pondE = createPond("NCD-E", "跨日期窗口池", "禁养E海区", "123.10", "25.10");
            pondF = createPond("NCD-F", "未来生效池", "禁养F海区", "124.10", "24.10");
            pondX = createPond("NCD-X", "转池目标池", "禁养X海区", "125.10", "23.10");

            batchTan = stockBatch("NCD-B-TAN", pondTangent, "2026-09-01");
            batchVtx = stockBatch("NCD-B-VTX", pondVertex, "2026-09-01");
            batchOut = stockBatch("NCD-B-OUT", pondNearOut, "2026-09-01");
            batchDIn = stockBatch("NCD-B-DIN", pondDIn, "2026-09-01");
            batchDOut = stockBatch("NCD-B-DOUT", pondDOut, "2026-09-01");
            batchE = stockBatch("NCD-B-E", pondE, "2026-09-01");
            batchF = stockBatch("NCD-B-F", pondF, "2026-09-01");
            Long preDone = stockBatch("NCD-B-DONE", pondDIn, "2026-08-01");
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches/" + preDone + "/harvest",
                    harvestBody("2026-09-20", 1000))), "规则前完成一批出池");
            batchDDone = preDone;
        });

        checkpoint("CP2", "边界相切按约定计入：登记多边形R1，切点/顶点批冻结并列冻结清单，转池单一律拦截；区外邻近点不误伤；重复登记幂等", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/noculture/rules",
                    ruleBody("NCD-R1", "禁养C海区", "POLYGON", polygon,
                            "2000-01-01", null, 2, "监管周海", "ncd-r1")));
            assertSuccess(resp, "登记多边形禁养规则R1");
            r1 = resp.path("data").path("rule").path("id").asLong();
            check(resp.path("data").path("frozenCount").asInt() == 3,
                    "R1应冻结3批（边线切点、顶点、多边形内D区），实际=" + resp.path("data").path("frozenCount"));
            check(resp.path("data").path("completedNoticeCount").asInt() == 1,
                    "R1应对多边形内已完成批只提示1次，实际=" + resp.path("data").path("completedNoticeCount"));

            // 冻结清单随发布事务生成并返回：三批含批次号
            JsonNode freezeList = resp.path("data").path("freezeList");
            check(freezeList.isArray() && freezeList.size() == 3,
                    "发布响应应带3行冻结清单，实际=" + freezeList);
            Set<String> frozenNos = new HashSet<>();
            freezeList.forEach(row -> frozenNos.add(row.path("batchNo").asText()));
            check(frozenNos.contains("NCD-B-TAN") && frozenNos.contains("NCD-B-VTX")
                            && frozenNos.contains("NCD-B-DIN"),
                    "冻结清单应含切点/顶点/区内三批，实际=" + frozenNos);

            check(freezeCount(r1, batchTan, NoCultureFreeze.STATUS_FROZEN) == 1, "边线相切批应被冻结");
            check(freezeCount(r1, batchVtx, NoCultureFreeze.STATUS_FROZEN) == 1, "顶点相切批应被冻结");
            check(freezeCount(r1, batchDIn, NoCultureFreeze.STATUS_FROZEN) == 1, "多边形内D区批应被冻结");
            check(freezeCount(r1, batchOut, null) == 0, "区外邻近批不得被冻结");
            check(freezeCount(r1, batchDOut, null) == 0, "多边形外D区批不得被冻结");
            check(freezeCount(null, batchDDone, null) == 0, "已完成批不得有冻结账（只提示不回写）");
            check(SeedlingBatch.STATUS_HARVESTED.equals(batchMapper.selectById(batchDDone).getStatus()),
                    "已完成批台账状态必须保持HARVESTED");

            // 报告问题的回归：新增禁养范围后，相切批的转池单不得再顺利提交
            JsonNode trTan = json(call(HttpMethod.POST, "/api/aqua/batches/" + batchTan + "/transfer",
                    transferBody(pondX, "2026-10-06")));
            check(!trTan.path("success").asBoolean(true) && trTan.path("message").asText().contains("禁养"),
                    "边线相切批转池应被拦截：" + trTan.path("message").asText());
            JsonNode trVtx = json(call(HttpMethod.POST, "/api/aqua/batches/" + batchVtx + "/transfer",
                    transferBody(pondX, "2026-10-06")));
            check(!trVtx.path("success").asBoolean(true) && trVtx.path("message").asText().contains("禁养"),
                    "顶点相切批转池应被拦截：" + trVtx.path("message").asText());
            JsonNode stockTan = json(call(HttpMethod.POST, "/api/aqua/batches",
                    stockBody("NCD-B-NEW-TAN", pondTangent, "2026-10-06")));
            check(!stockTan.path("success").asBoolean(true)
                            && stockTan.path("message").asText().contains("禁养范围"),
                    "向相切池投苗应被空间+时间拦截：" + stockTan.path("message").asText());

            // 区外邻近点不误伤：转池放行
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches/" + batchOut + "/transfer",
                    transferBody(pondX, "2026-10-06"))), "区外邻近批转池应放行（相切计入但不扩大）");

            JsonNode again = json(call(HttpMethod.POST, "/api/aqua/noculture/rules",
                    ruleBody("NCD-R1", "禁养C海区", "POLYGON", polygon,
                            "2000-01-01", null, 2, "监管周海", "ncd-r1")));
            check(again.path("data").path("idempotent").asBoolean()
                            && again.path("data").path("freezeList").size() == 3,
                    "重复登记应幂等回放同一冻结清单");
            check(freezeCount(r1, null, null) == 3, "重复登记不得新增冻结账");
        });

        checkpoint("CP3", "跨日期半开窗口的转池单：窗口前一天放行、窗口内拦截、结束日当天（半开端点）放行", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/noculture/rules",
                    ruleBody("NCD-R2", "禁养E海区", "AREA", null,
                            "2026-10-01", "2026-10-10", 1, "监管吴海", "ncd-r2")));
            assertSuccess(resp, "登记禁养E海区有限窗口规则R2");
            r2 = resp.path("data").path("rule").path("id").asLong();
            check(resp.path("data").path("frozenCount").asInt() == 1,
                    "R2应冻结禁养E海区在途批1批，实际=" + resp.path("data").path("frozenCount"));

            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches/" + batchE + "/transfer",
                    transferBody(pondX, "2026-09-30"))), "窗口前一天（跨日期）转池应放行");
            JsonNode inside = json(call(HttpMethod.POST, "/api/aqua/batches/" + batchE + "/transfer",
                    transferBody(pondE, "2026-10-05")));
            check(!inside.path("success").asBoolean(true) && inside.path("message").asText().contains("禁养"),
                    "窗口内转池应被拦截：" + inside.path("message").asText());
            JsonNode stockInside = json(call(HttpMethod.POST, "/api/aqua/batches",
                    stockBody("NCD-B-E1", pondE, "2026-10-05")));
            check(!stockInside.path("success").asBoolean(true), "窗口内向禁养E海区投苗应被拦截");
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches/" + batchE + "/transfer",
                    transferBody(pondE, "2026-10-10"))), "结束日当天（半开端点to）转池应放行");
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches",
                    stockBody("NCD-B-E2", pondE, "2026-10-10"))), "结束日当天投苗应放行");
        });

        checkpoint("CP4", "未来生效规则：发布事务即生成冻结清单，但不提前拦——生效日前转池放行、生效日起拦截", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/noculture/rules",
                    ruleBody("NCD-R3", "禁养F海区", "AREA", null,
                            "2027-01-01", null, 1, "监管郑海", "ncd-r3")));
            assertSuccess(resp, "登记未来生效规则R3");
            r3 = resp.path("data").path("rule").path("id").asLong();
            check(resp.path("data").path("frozenCount").asInt() == 1
                            && resp.path("data").path("freezeList").size() == 1,
                    "未来生效规则也应在发布事务生成冻结清单，实际=" + resp.path("data").path("frozenCount"));

            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches/" + batchF + "/transfer",
                    transferBody(pondX, "2026-12-01"))), "生效日前转池应放行（未来生效不提前拦）");
            JsonNode afterFrom = json(call(HttpMethod.POST, "/api/aqua/batches/" + batchF + "/transfer",
                    transferBody(pondF, "2027-01-02")));
            check(!afterFrom.path("success").asBoolean(true)
                            && afterFrom.path("message").asText().contains("禁养"),
                    "生效日起转池应被拦截：" + afterFrom.path("message").asText());
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches",
                    stockBody("NCD-B-F1", pondF, "2026-12-01"))), "生效日前投苗应放行");
            JsonNode stockAfter = json(call(HttpMethod.POST, "/api/aqua/batches",
                    stockBody("NCD-B-F2", pondF, "2027-01-02")));
            check(!stockAfter.path("success").asBoolean(true), "生效日起投苗应被拦截");
        });

        checkpoint("CP5", "多海区重叠：登记禁养D海区整海区R4（严格级5），与跨海区多边形R1重叠命中同批，按更严格裁决", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/noculture/rules",
                    ruleBody("NCD-R4", "禁养D海区", "AREA", null,
                            "2000-01-01", null, 5, "监管王海", "ncd-r4")));
            assertSuccess(resp, "登记禁养D海区整海区规则R4");
            r4 = resp.path("data").path("rule").path("id").asLong();
            check(resp.path("data").path("frozenCount").asInt() == 2,
                    "R4应冻结2批（多边形内D区批重叠再冻、多边形外D区批新冻），实际="
                            + resp.path("data").path("frozenCount"));
            check(resp.path("data").path("completedNoticeCount").asInt() == 1,
                    "R4应对已完成批再只提示一次，实际=" + resp.path("data").path("completedNoticeCount"));

            check(freezeCount(null, batchDIn, NoCultureFreeze.STATUS_FROZEN) == 2,
                    "多海区重叠批应同时被R1（多边形跨海区）与R4（整海区）冻结");
            check(freezeCount(r4, batchDOut, NoCultureFreeze.STATUS_FROZEN) == 1, "多边形外D区批应被R4冻结");
            check(matchMapper.selectCount(new QueryWrapper<NoCultureMatch>()
                    .eq("batch_id", batchDDone)
                    .eq("result", NoCultureMatch.RESULT_COMPLETED_NOTICE)) == 2L,
                    "已完成批应在R1/R4各留1条只提示匹配，始终不回写台账");

            JsonNode stockOverlap = json(call(HttpMethod.POST, "/api/aqua/batches",
                    stockBody("NCD-B-OVL", pondDIn, "2026-10-06")));
            check(!stockOverlap.path("success").asBoolean(true)
                            && stockOverlap.path("message").asText().contains("NCD-R4")
                            && stockOverlap.path("message").asText().contains("更严格"),
                    "多海区重叠命中应按更严格规则R4裁决：" + stockOverlap.path("message").asText());
            JsonNode trDOut = json(call(HttpMethod.POST, "/api/aqua/batches/" + batchDOut + "/transfer",
                    transferBody(pondX, "2026-10-06")));
            check(!trDOut.path("success").asBoolean(true), "R4冻结的多边形外D区批转池应被拦截");
        });

        checkpoint("CP6", "撤销跨海区重叠：R1撤销后重叠批仍被R4挂起（SKIPPED_STILL_BOUND），R4撤销后彻底放行；勾稽平衡", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/noculture/rules/" + r1 + "/revoke",
                    revokeBody("监管周海", "规划调整撤销R1", "ncd-r1-rev")));
            assertSuccess(resp, "撤销R1");
            check(resp.path("data").path("releasedCount").asInt() == 2,
                    "R1撤销应放行2批（切点/顶点），实际=" + resp.path("data").path("releasedCount"));
            check(resp.path("data").path("skippedStillBoundCount").asInt() == 1,
                    "重叠批仍被R4冻结应挂起1笔，实际=" + resp.path("data").path("skippedStillBoundCount"));

            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches/" + batchTan + "/transfer",
                    transferBody(pondX, "2026-10-06"))), "R1撤销后相切批转池应放行");
            JsonNode stillBound = json(call(HttpMethod.POST, "/api/aqua/batches/" + batchDIn + "/transfer",
                    transferBody(pondX, "2026-10-06")));
            check(!stillBound.path("success").asBoolean(true), "仍被R4冻结的重叠批转池应继续拦截");

            JsonNode reconcile1 = json(call(HttpMethod.GET,
                    "/api/aqua/noculture/rules/" + r1 + "/reconcile", null));
            check(reconcile1.path("data").path("balanced").asBoolean(), "R1撤销后应账账对应");

            JsonNode resp4 = json(call(HttpMethod.POST, "/api/aqua/noculture/rules/" + r4 + "/revoke",
                    revokeBody("监管王海", "文件到期撤销R4", "ncd-r4-rev")));
            assertSuccess(resp4, "撤销R4");
            check(resp4.path("data").path("releasedCount").asInt() == 2
                            && resp4.path("data").path("skippedStillBoundCount").asInt() == 0,
                    "R4撤销后其冻结批应全部RELEASED");
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/batches/" + batchDIn + "/transfer",
                    transferBody(pondX, "2026-10-06"))), "全部撤销后重叠批转池应放行");
            JsonNode reconcile4 = json(call(HttpMethod.GET,
                    "/api/aqua/noculture/rules/" + r4 + "/reconcile", null));
            check(reconcile4.path("data").path("balanced").asBoolean(), "R4撤销后应账账对应");
        });

        checkpoint("CP7", "缺关键字段一次性指出全部缺项；补齐后同requestId只重试当前步骤（幂等不重复落单）", () -> {
            Map<String, Object> missing = ruleBody("NCD-R7", "禁养G海区", "AREA", null,
                    "2000-01-01", null, 1, null, null);
            missing.remove("basisDocNo");
            missing.remove("basisDocName");
            missing.remove("operator");
            missing.remove("requestId");
            JsonNode rejected = json(call(HttpMethod.POST, "/api/aqua/noculture/rules", missing));
            String message = rejected.path("message").asText();
            check(!rejected.path("success").asBoolean(true)
                            && message.contains("依据文件文号不能为空")
                            && message.contains("依据文件名称不能为空")
                            && message.contains("操作人不能为空")
                            && message.contains("requestId不能为空"),
                    "缺4个关键字段应一次性全部指出，实际=" + message);
            check(ruleMapper.selectCount(new QueryWrapper<NoCultureRule>()
                    .eq("rule_no", "NCD-R7")) == 0L, "缺项请求不得落规则");

            JsonNode created = json(call(HttpMethod.POST, "/api/aqua/noculture/rules",
                    ruleBody("NCD-R7", "禁养G海区", "AREA", null,
                            "2000-01-01", null, 1, "监管周海", "ncd-r7")));
            assertSuccess(created, "补齐缺项后重试当前步骤应成功");
            r7 = created.path("data").path("rule").path("id").asLong();
            check(created.path("data").path("freezeList").size() == 0,
                    "禁养G海区无池塘，冻结清单应为空");

            JsonNode replay = json(call(HttpMethod.POST, "/api/aqua/noculture/rules",
                    ruleBody("NCD-R7", "禁养G海区", "AREA", null,
                            "2000-01-01", null, 1, "监管周海", "ncd-r7")));
            check(replay.path("data").path("idempotent").asBoolean()
                            && replay.path("data").path("rule").path("id").asLong() == r7,
                    "同requestId重试应幂等返回同一规则");
            check(ruleMapper.selectCount(new QueryWrapper<NoCultureRule>()
                    .eq("request_id", "ncd-r7")) == 1L, "重试不得重复落单");
        });

        checkpoint("CP8", "业务失败保留原始凭证：重复编号登记/撤销不存在规则失败均留证，可按requestId追溯原始报文", () -> {
            Map<String, Object> dup = ruleBody("NCD-R7", "禁养G海区", "AREA", null,
                    "2000-01-01", null, 1, "监管周海", "ncd-r8-dup");
            dup.put("basisDocNo", "DOCK-2026-NCD-R8DUP");
            JsonNode dupResp = json(call(HttpMethod.POST, "/api/aqua/noculture/rules", dup));
            check(!dupResp.path("success").asBoolean(true)
                            && dupResp.path("message").asText().contains("已存在"),
                    "重复规则编号应被拒绝：" + dupResp.path("message").asText());
            check(ruleMapper.selectCount(new QueryWrapper<NoCultureRule>()
                    .eq("request_id", "ncd-r8-dup")) == 0L, "失败登记不得落规则");

            JsonNode evCreate = json(call(HttpMethod.GET,
                    "/api/aqua/noculture/evidence?requestId=ncd-r8-dup", null));
            assertSuccess(evCreate, "按requestId查询失败登记证据");
            check(evCreate.path("data").size() == 1
                            && NoCultureChangeEvidence.ACTION_CREATE_RULE_FAILED.equals(
                                    evCreate.path("data").get(0).path("action").asText())
                            && "监管周海".equals(evCreate.path("data").get(0).path("operator").asText())
                            && evCreate.path("data").get(0).path("afterJson").asText().contains("NCD-R7")
                            && evCreate.path("data").get(0).path("afterJson").asText().contains("DOCK-2026-NCD-R8DUP"),
                    "失败登记应留CREATE_RULE_FAILED证据且含原始请求凭证，实际=" + evCreate.path("data"));

            JsonNode revResp = json(call(HttpMethod.POST, "/api/aqua/noculture/rules/999999/revoke",
                    revokeBody("监管吴海", "越权撤销尝试-留证", "ncd-r8-rev")));
            check(!revResp.path("success").asBoolean(true), "撤销不存在的规则应失败");
            JsonNode evRevoke = json(call(HttpMethod.GET,
                    "/api/aqua/noculture/evidence?requestId=ncd-r8-rev", null));
            check(evRevoke.path("data").size() == 1
                            && NoCultureChangeEvidence.ACTION_REVOKE_RULE_FAILED.equals(
                                    evRevoke.path("data").get(0).path("action").asText())
                            && "监管吴海".equals(evRevoke.path("data").get(0).path("operator").asText())
                            && evRevoke.path("data").get(0).path("afterJson").asText().contains("越权撤销尝试-留证"),
                    "失败撤销应留REVOKE_RULE_FAILED证据且含原始请求凭证，实际=" + evRevoke.path("data"));
        });

        report.add("验收结论：全部检查点通过——边界相切按约定计入命中且区外不误伤，发布事务生成冻结清单，"
                + "跨日期半开窗口与未来生效不提前拦，多海区重叠按更严格裁决且撤销挂起可勾稽，"
                + "缺项一次性指出且补齐后幂等重试，业务失败保留原始凭证");
        printReport();
    }

    // ------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------

    private long freezeCount(Long ruleId, Long batchId, String status) {
        QueryWrapper<NoCultureFreeze> wrapper = new QueryWrapper<>();
        wrapper.eq(ruleId != null, "rule_id", ruleId);
        wrapper.eq(batchId != null, "batch_id", batchId);
        wrapper.eq(status != null, "status", status);
        Long c = freezeMapper.selectCount(wrapper);
        return c == null ? 0L : c;
    }

    private Long createPond(String code, String name, String seaArea, String lon, String lat) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("pondCode", code);
        body.put("pondName", name);
        body.put("seaArea", seaArea);
        body.put("waterDepthM", "1.80");
        body.put("salinityPpt", "28.5");
        body.put("longitude", lon);
        body.put("latitude", lat);
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/ponds", body));
        assertSuccess(resp, "建池[" + code + "]");
        return resp.path("data").path("id").asLong();
    }

    private Long stockBatch(String batchNo, Long pondId, String stockDate) throws Exception {
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/batches", stockBody(batchNo, pondId, stockDate)));
        assertSuccess(resp, "投苗[" + batchNo + "]");
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> stockBody(String batchNo, Long pondId, String stockDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("batchNo", batchNo);
        body.put("pondId", pondId);
        body.put("species", "LARGE_YELLOW_CROAKER");
        body.put("seedSource", "东山湾苗种场");
        body.put("quantity", 1000);
        body.put("stockDate", stockDate);
        body.put("plannedHarvestDate", "2027-12-31");
        return body;
    }

    private Map<String, Object> transferBody(Long toPondId, String transferDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("toPondId", toPondId);
        body.put("transferDate", transferDate);
        body.put("reason", "禁养跨日期/重叠验收转池");
        return body;
    }

    private Map<String, Object> harvestBody(String date, int qty) {
        Map<String, Object> body = new HashMap<>();
        body.put("harvestDate", date);
        body.put("quantity", qty);
        return body;
    }

    private Map<String, Object> ruleBody(String ruleNo, String seaArea, String scopeType,
                                         List<List<Double>> polygonPoints, String from, String to,
                                         int strictLevel, String operator, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ruleNo", ruleNo);
        if (seaArea != null) {
            body.put("seaArea", seaArea);
        }
        body.put("scopeType", scopeType);
        if (polygonPoints != null) {
            body.put("polygon", polygonPoints);
        }
        body.put("effectiveFrom", from);
        if (to != null) {
            body.put("effectiveTo", to);
        }
        body.put("basisDocNo", "DOCK-2026-" + ruleNo);
        body.put("basisDocName", "海区禁养划定文件");
        body.put("strictLevel", strictLevel);
        if (operator != null) {
            body.put("operator", operator);
        }
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }

    private Map<String, Object> revokeBody(String operator, String reason, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("operator", operator);
        body.put("reason", reason);
        body.put("requestId", requestId);
        return body;
    }

    private void checkpoint(String id, String name, CheckpointBody body) {
        try {
            body.run();
            String line = "通过 " + id + " " + name;
            report.add(line);
            System.out.println("[验收] " + line);
        } catch (Throwable t) {
            String stop = "停点 " + id + "「" + name + "」失败，原因：" + t.getMessage();
            report.add(stop);
            report.add("验收结论：未通过，后续检查点未执行");
            printReport();
            fail("验收" + stop);
        }
    }

    private void printReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== 海区禁养跨日期与多海区重叠验收报告 ==========\n");
        for (String line : report) {
            sb.append(line).append('\n');
        }
        sb.append("==========================================\n");
        System.out.println(sb);
    }

    private ResponseEntity<String> call(HttpMethod method, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }
        return rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertSuccess(JsonNode node, String what) {
        if (!node.path("success").asBoolean(false)) {
            throw new IllegalStateException(what + "接口返回失败：" + node.path("message").asText());
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private interface CheckpointBody {
        void run() throws Exception;
    }
}
