package com.rc.signaling.dao;

import com.rc.common.model.Device;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code device} 表访问。
 */
@Mapper
public interface DeviceMapper {

    String COLUMNS = "id, user_id, device_code, device_name, os, version, connect_password_hash, "
            + "device_public_key, public_key_fingerprint, nat_type, last_online_at, status";

    @Insert("INSERT INTO `device` (user_id, device_code, device_name, os, version, "
            + "connect_password_hash, device_public_key, public_key_fingerprint, nat_type, last_online_at, status) "
            + "VALUES (#{userId}, #{deviceCode}, #{deviceName}, #{os}, #{version}, "
            + "#{connectPasswordHash}, #{devicePublicKey}, #{publicKeyFingerprint}, #{natType}, #{lastOnlineAt}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Device device);

    @Select("SELECT " + COLUMNS + " FROM `device` WHERE id = #{id}")
    Device findById(long id);

    @Select("SELECT " + COLUMNS + " FROM `device` WHERE device_code = #{deviceCode}")
    Device findByDeviceCode(String deviceCode);

    @Select("SELECT " + COLUMNS + " FROM `device` WHERE user_id = #{userId} ORDER BY last_online_at DESC")
    List<Device> findByUserId(long userId);

    @Update("UPDATE `device` SET device_name = #{deviceName}, os = #{os}, version = #{version}, "
            + "device_public_key = #{devicePublicKey}, public_key_fingerprint = #{publicKeyFingerprint}, "
            + "nat_type = #{natType}, last_online_at = #{lastOnlineAt}, status = #{status} "
            + "WHERE id = #{id}")
    int updateOnlineInfo(Device device);

    @Update("UPDATE `device` SET status = #{status}, last_online_at = #{lastOnlineAt} WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") int status, @Param("lastOnlineAt") long lastOnlineAt);
}
