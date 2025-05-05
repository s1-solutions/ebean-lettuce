package org.integration;

import io.ebean.DB;
import io.ebean.Database;
import io.ebean.lettuce.DuelCache;
import org.domain.Person;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterTest {

  private Database createOther(DataSource dataSource) {
    return Database.builder()
      .dataSource(dataSource)
      .loadFromProperties()
      .defaultDatabase(false)
      .name("other")
      .ddlGenerate(false)
      .ddlRun(false)
      .build();
  }

  @Test
  void testBothNear() throws InterruptedException {
    // ensure the default server exists first
    final Database db = DB.getDefault();
    Database other = createOther(db.pluginApi().dataSource());


    Person oldFoo = Person.find.query().where().eq("name", "foo").findOne();
    if(oldFoo != null) {
      oldFoo.delete();
    }

    Person foo = new Person("Someone");
    foo.save();

    DB.cacheManager().clearAll();
    db.metaInfo().resetAllMetrics();
    other.metaInfo().resetAllMetrics();

    Person fooA = DB.find(Person.class, foo.getId());
    allowAsyncMessaging(); // allow time for background cache load
    Person fooB = other.find(Person.class, foo.getId());

    DuelCache dualCacheA = db.cacheManager().beanCache(Person.class).unwrap(DuelCache.class);
    assertCounts(dualCacheA, 0, 1, 0, 1);
    fooA = DB.find(Person.class, foo.getId());
    assertCounts(dualCacheA, 1, 1, 0, 1);
    fooB = other.find(Person.class, foo.getId());
    fooA = DB.find(Person.class, foo.getId());
    assertCounts(dualCacheA, 2, 1, 0, 1);
    fooB = other.find(Person.class, foo.getId());
    DuelCache dualCacheB = other.cacheManager().beanCache(Person.class).unwrap(DuelCache.class);
    assertCounts(dualCacheB, 2, 1, 1, 0);
  }

  @Test
  void test() throws InterruptedException {
    // ensure the default server exists first
    final Database db = DB.getDefault();
    Database other = createOther(db.pluginApi().dataSource());

    for (int i = 0; i < 10; i++) {
      Person foo = new Person("name " + i);
      foo.save();
    }

    other.cacheManager().clearAll();
    other.metaInfo().resetAllMetrics();

    DuelCache dualCache = other.cacheManager().beanCache(Person.class).unwrap(DuelCache.class);

    Person foo0 = other.find(Person.class, 1);
    assertCounts(dualCache, 0, 1, 0, 1);

    other.find(Person.class, 1);
    assertCounts(dualCache, 1, 1, 0, 1);

    other.find(Person.class, 1);
    assertCounts(dualCache, 2, 1, 0, 1);

    other.find(Person.class, 1);
    assertCounts(dualCache, 3, 1, 0, 1);

    other.find(Person.class, 2);
    assertCounts(dualCache, 3, 2, 0, 2);

    foo0.setName("name2");
    foo0.save();
    allowAsyncMessaging();

    Person foo3 = other.find(Person.class, 1);
    assertThat(foo3.getName()).isEqualTo("name2");
    assertCounts(dualCache, 3, 3, 1, 2);

    foo0.setName("name3");
    foo0.save();
    allowAsyncMessaging();

    foo3 = other.find(Person.class, 1);
    assertThat(foo3.getName()).isEqualTo("name3");
    assertCounts(dualCache, 3, 4, 2, 2);
  }

  private void assertCounts(DuelCache dualCache, int nearHits, int nearMiss, int remoteHit, int remoteMiss) {
    assertThat(dualCache.getNearHitCount()).isEqualTo(nearHits);
    assertThat(dualCache.getNearMissCount()).isEqualTo(nearMiss);
    assertThat(dualCache.getRemoteHitCount()).isEqualTo(remoteHit);
    assertThat(dualCache.getRemoteMissCount()).isEqualTo(remoteMiss);
  }

  private void allowAsyncMessaging() throws InterruptedException {
    Thread.sleep(200);
  }
}
