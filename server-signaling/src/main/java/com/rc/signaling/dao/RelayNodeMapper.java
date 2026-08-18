package com.rc.signaling.dao;

import com.rc.common.model.RelayNode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code relay_node} 表访问。节点心跳 upsert 由 {@link com.rc.signaling.service.RelayManager}
 * 编排（先查后插/改，兼容 H2 与 MySQL），调度时读取在线节点按 region / load_ratio 就近择优。
 */
@Mapper
public interface RelayNodeMapper {

    String COLUMNS = "id, node_id, host, region, udp_port, tcp_port, ws_port, tls, "
            + "load_ratio, status, last_heartbeat_at";

    @Insert("INSERT INTO `relay_node` (node_id, host, region, udp_port, tcp_port, ws_port, tls, "
            + "load_ratio, status, last_heartbeat_at) VALUES "
            + "(#{nodeId}, #{host}, #{region}, #{udpPort}, #{tcpPort}, #{wsPort}, #{tls}, "
            + "#{loadRatio}, #{status}, #{lastHeartbeatAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RelayNode node);

    @Update("UPDATE `relay_node` SET host = #{host}, region = #{region}, udp_port = #{udpPort}, "
            + "tcp_port = #{tcpPort}, ws_port = #{wsPort}, tls = #{tls}, load_ratio = #{loadRatio}, "
            + "status = #{status}, last_heartbeat_at = #{lastHeartbeatAt} WHERE id = #{id}")
    int updateHeartbeat(RelayNode node);

    @Select("SELECT " + COLUMNS + " FROM `relay_node` WHERE node_id = #{nodeId}")
    RelayNode findByNodeId(String nodeId);

    @Select("SELECT " + COLUMNS + " FROM `relay_node` WHERE status = 1 ORDER BY load_ratio ASC")
    List<RelayNode> listOnline();

    @Select("SELECT " + COLUMNS + " FROM `relay_node` WHERE region = #{region} AND status = 1 "
            + "ORDER BY load_ratio ASC")
    List<RelayNode> listOnlineByRegion(String region);

    @Update("UPDATE `relay_node` SET status = 0 WHERE status = 1 AND last_heartbeat_at < #{cutoff}")
    int markStale(@Param("cutoff") long cutoff);
}
