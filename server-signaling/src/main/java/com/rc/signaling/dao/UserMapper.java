package com.rc.signaling.dao;

import com.rc.common.model.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/**
 * {@code user} 表访问（MyBatis 注解映射，避免 XML）。
 */
@Mapper
public interface UserMapper {

    @Insert("INSERT INTO `user` (username, password_hash, sso_subject, role, created_at) "
            + "VALUES (#{username}, #{passwordHash}, #{ssoSubject}, #{role}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Select("SELECT id, username, password_hash, sso_subject, role, created_at "
            + "FROM `user` WHERE username = #{username}")
    User findByUsername(String username);

    @Select("SELECT id, username, password_hash, sso_subject, role, created_at "
            + "FROM `user` WHERE id = #{id}")
    User findById(long id);

    @Select("SELECT id, username, password_hash, sso_subject, role, created_at "
            + "FROM `user` WHERE sso_subject = #{ssoSubject}")
    User findBySsoSubject(String ssoSubject);
}
