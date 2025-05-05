package org.domain;


import io.ebean.Finder;
import io.ebean.annotation.Cache;
import jakarta.persistence.Entity;

/**
 * Using Natural Key caching but no Near Caching so always hitting Redis.
 */
@SuppressWarnings("unused")
@Cache(naturalKey = {"one", "two"})
@Entity
public class OtherOne extends EBase {

        public static final Finder<Long, OtherOne> find = new Finder<>(OtherOne.class);

  private final String one;
  private final String two;
  private String notes;

  public OtherOne(String one, String two, String notes) {
    this.one = one;
    this.two = two;
    this.notes = notes;
  }

  public String one() {
    return one;
  }

  public String two() {
    return two;
  }

  public String notes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
