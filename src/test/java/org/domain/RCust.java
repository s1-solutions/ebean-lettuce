package org.domain;


import io.ebean.Finder;
import io.ebean.annotation.Cache;
import io.ebean.annotation.CacheBeanTuning;
import io.ebean.annotation.Index;

import jakarta.persistence.Entity;
import java.time.LocalDate;

@Cache(naturalKey = "name")
@Entity
public class RCust extends EBase {

      public static final Finder<Long, RCust> find = new Finder<>(RCust.class);


  @Index(unique = true)
  String name;

  public RCust(String name) {
    this.name = name;
  }

}
