package org.integration;

import io.ebean.DB;
import io.ebean.cache.ServerCache;
import io.ebean.cache.ServerCacheStatistics;
import org.domain.*;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTest {

    @Test
    void uuid_getPut() {
        UParent b0 = new UParent("b0");
        b0.children().add(new UChild(b0, "b0c0"));
        b0.children().add(new UChild(b0, "b0c1"));
        b0.save();

        ServerCache beanCache = DB.cacheManager().beanCache(UParent.class);
        beanCache.clear();
        beanCache.statistics(true);

        UParent found0 = DB.find(UParent.class, b0.id());
        assertThat(found0.name()).isEqualTo("b0");

        List<UChild> children = found0.children();
        assertThat(children).hasSize(2);

        UParent found1 = DB.find(UParent.class, b0.id());
        assertThat(found1.name()).isEqualTo("b0");

        DB.delete(found1);

        ServerCacheStatistics stats1 = beanCache.statistics(true);
        assertThat(stats1.getHitCount()).isEqualTo(1);
    }

    @Test
    void mget_when_emptyCollectionOfIds() {

        List<RCust> f0 = RCust.find.query().where().idIn(new ArrayList<>())
                .findList();

        assertThat(f0).isEmpty();

        List<RCust> f1 = RCust.find.query().where().idIn(Collections.emptyList()).findList();

        assertThat(f1).isEmpty();
    }

    @Test
    void mput_via_setIdIn() throws InterruptedException {

        ServerCache beanCache = DB.cacheManager().beanCache(RCust.class);
        beanCache.clear();
        beanCache.statistics(true);

        List<RCust> people = new ArrayList<>();
        for (String name : new String[]{"mp0", "mp1", "mp2"}) {
            people.add(new RCust(name));
        }
        DB.saveAll(people);
        List<Long> ids = people.stream().map(RCust::getId).collect(Collectors.toList());

        List<RCust>
                f0 = RCust.find.query()
                .where()
                .idIn(ids)
                .findList();

        assertThat(f0).hasSize(3);
        ServerCacheStatistics stats0 = beanCache.statistics(true);
        assertThat(stats0.getHitCount()).isEqualTo(0);

        Thread.sleep(5);

        // we will hit the cache this time
        List<RCust> f1 =
                RCust.find.query()
                        .where()
                        .idIn(ids.toArray())
                        .findList();

        assertThat(f1).hasSize(3);
        ServerCacheStatistics stats1 = beanCache.statistics(true);
        assertThat(stats1.getHitCount()).isEqualTo(3);

        // we will hit the cache again
        List<RCust> f2 =
                RCust.find.query().where().idIn(ids).findList();


        assertThat(f2).hasSize(3);
        ServerCacheStatistics stats2 = beanCache.statistics(true);
        assertThat(stats2.getHitCount()).isEqualTo(3);
    }


    @Test
    void mput_via_propertyInExpression() throws InterruptedException {

        ServerCache beanCache = DB.cacheManager().beanCache(RCust.class);
        beanCache.clear();
        beanCache.statistics(true);

        List<RCust> people = new ArrayList<>();
        for (String name : new String[]{"mpx0", "mpx1", "mpx2"}) {
            people.add(new RCust(name));
        }
        DB.saveAll(people);
        List<Long> ids = people.stream().map(RCust::getId).collect(Collectors.toList());

        List<RCust>
                f0 = RCust.find.query()
                .where()
                .idIn(ids)
                .findList();

        assertThat(f0).hasSize(3);
        ServerCacheStatistics stats0 = beanCache.statistics(true);
        assertThat(stats0.getHitCount()).isEqualTo(0);

        Thread.sleep(5);


        List<RCust>
                f1 = RCust.find.query()
                .where()
                .idIn(ids)
                .findList();

        assertThat(f1).hasSize(3);
        ServerCacheStatistics stats1 = beanCache.statistics(true);
        assertThat(stats1.getHitCount()).isEqualTo(3);


        List<RCust>
                f2 = RCust.find.query()
                .where()
                .idIn(ids)
                .findList();

        assertThat(f2).hasSize(3);
        ServerCacheStatistics stats2 = beanCache.statistics(true);
        assertThat(stats2.getHitCount()).isEqualTo(3);
    }

    @Test
    void testOtherOne() {
        DB.save(new OtherOne("A", "B", "ab"));
        DB.save(new OtherOne("A", "C", "ac"));
        DB.save(new OtherOne("B", "B", "bb"));

        ServerCache nkeyCache = DB.cacheManager().naturalKeyCache(OtherOne.class);
        nkeyCache.clear();
        nkeyCache.statistics(true);

        OtherOne ab0 = findOther("A", "B");
        OtherOne ab1 = findOther("A", "B");
        OtherOne ab2 = findOther("A", "B");
        OtherOne bb = findOther("B", "B");

        assertThat(ab0).isNotNull();
        assertThat(ab1).isNotNull();
        assertThat(ab2).isNotNull();
        assertThat(bb).isNotNull();

        ServerCacheStatistics statistics = nkeyCache.statistics(true);
        assertThat(statistics.getHitCount()).isEqualTo(2);
    }

    private static OtherOne findOther(String a, String b) {

        return OtherOne.find.query()
                .where()
                .eq("one", a)
                .eq("two", b)
                .findOne();
    }

    @Test
    void test() throws InterruptedException {

        insertSomePeople();

        Person fiona = findByName("Fiona");
        fiona.setName("Fortuna");
        fiona.setLocalDate(LocalDate.now());
        fiona.update();

        Thread.sleep(100);

        Person one = findById(1);
        assertThat(one).isNotNull();

        for (int i = 1; i < 4; i++) {
            System.out.println("hit " + findById(i));
        }

        List<Person> one2 = nameStartsWith("fo");
        assertThat(one2).hasSize(1);

        one2 = nameStartsWith("j");
        assertThat(one2).hasSize(2);

        one2 = nameStartsWith("j");
        assertThat(one2).hasSize(2);


        List<Person> byNames = findByNames("Jack", "Rob");
        assertThat(byNames).hasSize(2);

        byNames = findByNames("Jack", "Rob", "Moby");
        assertThat(byNames).hasSize(3);

        fiona.setName("fo2");
        fiona.setLocalDate(LocalDate.now());
        fiona.update();

        byNames = findByNames("Jack", "Rob", "Moby");
        assertThat(byNames).hasSize(3);

        Thread.sleep(200);

        one2 = nameStartsWith("fo%");
        System.out.println("one2 " + one2);
        one2 = nameStartsWith("f0%");

        System.out.println("one2 " + one2);

        DB.cacheManager().clear(Person.class);

        System.out.println("done");
    }

    private void insertSomePeople() {
        List<Person> people = new ArrayList<>();
        for (String name : new String[]{"Jack", "John", "Rob", "Moby", "Fiona"}) {
            people.add(new Person(name));
        }
        DB.saveAll(people);
    }

    private Person findByName(String name) {
        return Person.find.query()
                .where()
                .eq("name", name)
                .findOne();
    }

    private List<Person> findByNames(String... names) {
        return Person.find.query()
                .where()
                .in("name", names)
                .findList();
    }

    private Person findById(int id) {
        return Person.find.byId((long) id);
    }

    private List<Person> nameStartsWith(String pattern) {

        return Person.find.query()
                .where()
                .ilike("name", pattern + "%")
                .setUseQueryCache(true)
                .findList();
    }
}
