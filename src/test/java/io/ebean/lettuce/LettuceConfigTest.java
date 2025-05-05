package io.ebean.lettuce;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class LettuceConfigTest {

  @Test
  public void loadProperties() {

    Properties p = new Properties();
    p.setProperty("ebean.lettuce.server", "test-server");
    p.setProperty("ebean.lettuce.port", "99");
    p.setProperty("ebean.lettuce.maxIdle", "5");
    p.setProperty("ebean.lettuce.maxTotal", "6");
    p.setProperty("ebean.lettuce.minIdle", "7");
    p.setProperty("ebean.lettuce.maxWaitMillis", "8");
    p.setProperty("ebean.lettuce.username", "un");
    p.setProperty("ebean.lettuce.password", "pw");
    p.setProperty("ebean.lettuce.ssl", "true");

    LettuceConfig config = new LettuceConfig();
    config.loadProperties(p);

    assertThat(config.getServer()).isEqualTo("test-server");
    assertThat(config.getPort()).isEqualTo(99);
    assertThat(config.getMaxIdle()).isEqualTo(5);
    assertThat(config.getMaxTotal()).isEqualTo(6);
    assertThat(config.getMinIdle()).isEqualTo(7);
    assertThat(config.getMaxWaitMillis()).isEqualTo(8);
    assertThat(config.getUsername()).isEqualTo("un");
    assertThat(config.getPassword()).isEqualTo("pw");
    assertThat(config.isSsl()).isEqualTo(true);
  }

  @Test
  public void test_defaultValues() {
    LettuceConfig config = new LettuceConfig();
    assertThat(config.getUsername()).isNull();
    assertThat(config.getPassword()).isNull();
    assertThat(config.isSsl()).isEqualTo(false);
  }
}
