package org.heigit.bigspatialdata.oshdb.api.mapreducer;

import java.io.Serializable;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.Collectors;

import com.vividsolutions.jts.geom.Polygon;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.heigit.bigspatialdata.oshdb.OSHDB;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDB_Ignite;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDB_JDBC;
import org.heigit.bigspatialdata.oshdb.api.generic.NumberUtils;
import org.heigit.bigspatialdata.oshdb.api.objects.OSHDBTimestamp;
import org.heigit.bigspatialdata.oshdb.api.objects.OSHDBTimestamps;
import org.heigit.bigspatialdata.oshdb.api.objects.OSMContribution;
import org.heigit.bigspatialdata.oshdb.api.objects.OSMEntitySnapshot;
import org.heigit.bigspatialdata.oshdb.index.XYGridTree;
import org.heigit.bigspatialdata.oshdb.osh.OSHEntity;
import org.heigit.bigspatialdata.oshdb.osm.OSMEntity;
import org.heigit.bigspatialdata.oshdb.osm.OSMType;
import org.heigit.bigspatialdata.oshdb.util.*;
import org.heigit.bigspatialdata.oshdb.util.tagInterpreter.TagInterpreter;

/**
 * Main class of oshdb's "functional programming" API.
 * Takes some settings and filters and exposes a different map-reduce/aggregate functions.
 *
 * @param <T> the type that is used to iterate over. The `mapper` function get called with an object of this type
 */
public abstract class MapReducer<T> {
  protected OSHDB _oshdb;
  protected OSHDB_JDBC _oshdbForTags;
  protected Class _forClass = null;
  private BoundingBox _bboxFilter = null;
  private Polygon _polyFilter = null;
  private OSHDBTimestamps _tstamps = null;
  private final List<SerializablePredicate<OSHEntity>> _preFilters = new ArrayList<>();
  private final List<SerializablePredicate<OSMEntity>> _filters = new ArrayList<>();
  protected TagInterpreter _tagInterpreter = null;
  protected TagTranslator _tagTranslator = null;
  protected EnumSet<OSMType> _typeFilter = EnumSet.allOf(OSMType.class);

  protected MapReducer(OSHDB oshdb) {
    this._oshdb = oshdb;
  }

  /**
   * Factory function that creates the mapReducer object of the appropriate class for the chosen backend (e.g. JDBC based database or Ignite)
   *
   * @param oshdb the database backend (and potentially computation grid) to use for fetching and processing the data
   * @param forClass the class (same as template parameter &lt;T&gt;) to iterate over in the `mapping` function
   * @param <T> the type that is used to iterate over in the `mapper` function
   * @return
   */
  public static <T> MapReducer<T> using(OSHDB oshdb, Class<?> forClass) {
    if (oshdb instanceof OSHDB_JDBC) {
      MapReducer<T> mapper;
      if (((OSHDB_JDBC)oshdb).multithreading())
        mapper = new MapReducer_JDBC_multithread<T>(oshdb);
      else
        mapper = new MapReducer_JDBC_singlethread<T>(oshdb);
      mapper._oshdb = oshdb;
      mapper._oshdbForTags = (OSHDB_JDBC)oshdb;
      mapper._forClass = forClass;
      return mapper;
    } else if (oshdb instanceof OSHDB_Ignite) {
      MapReducer<T> mapper = new MapReducer_Ignite<T>(oshdb);
      mapper._oshdbForTags = null;
      mapper._forClass = forClass;
      return mapper;
    } else {
      throw new UnsupportedOperationException("No mapper implemented for your database type");
    }
  }

  /**
   * Sets the keytables database to use in the calculations to resolve strings (osm tags, roles) into internally used identifiers.
   * If this function is never called, the main database (specified during the construction of this object) is used for this.
   *
   * @param oshdb the database to use for resolving strings into internal identifiers
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  public MapReducer<T> keytables(OSHDB_JDBC oshdb) {
    this._oshdbForTags = oshdb;
    return this;
  }

  /**
   * Helper that resolves an OSM tag key string into an internal identifier (using the keytables database).
   *
   * @param key the osm tag key string (e.g. "building")
   * @return the identifier of this tag key
   * @throws Exception
   */
  protected Integer getTagKeyId(String key) throws Exception {
    if (this._tagTranslator == null) this._tagTranslator = new TagTranslator((this._oshdbForTags).getConnection());
    return this._tagTranslator.key2Int(key);
  }

  /**
   * Helper that resolves an OSM tag (key and value) string into an internal identifier (using the keytables database).
   *
   * @param key the osm tag key string (e.g. "highway")
   * @param value the osm tag value string (e.g. "residential")
   * @return the key's and value's identifiers of this tag key and value respectively
   * @throws Exception
   */
  protected Pair<Integer, Integer> getTagValueId(String key, String value) throws Exception {
    if (this._tagTranslator == null) this._tagTranslator = new TagTranslator((this._oshdbForTags).getConnection());
    return this._tagTranslator.tag2Int(new ImmutablePair(key,value));
  }

  /**
   * Sets the tagInterpreter to use in the analysis.
   * The tagInterpreter is used internally to determine the geometry type of osm entities (e.g. an osm way can become either
   * a LineString or a Polygon, depending on its tags).
   * Normally, this is generated automatically for the user. But for example, if  one doesn't want to use the DefaultTagInterpreter,
   * it is possible to use this function to supply their own tagInterpreter.
   *
   * @param tagInterpreter the tagInterpreter object to use in the processing of osm entities
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  public MapReducer<T> tagInterpreter(TagInterpreter tagInterpreter) {
    this._tagInterpreter = tagInterpreter;
    return this;
  }

  /**
   * Set the area of interest to the given bounding box. Deprecated, use `areaOfInterest()` instead (w/ same semantics).
   *
   * @param bbox the bounding box to query the data in
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  @Deprecated
  public MapReducer<T> boundingBox(BoundingBox bbox) {
    return this.areaOfInterest(bbox);
  }

  /**
   * Set the area of interest to the given bounding box.
   * Only objects inside or clipped by this bbox will be passed on to the analysis' `mapper` function.
   *
   * @param bboxFilter the bounding box to query the data in
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  public MapReducer<T> areaOfInterest(BoundingBox bboxFilter) {
    if (this._polyFilter == null) {
      if (this._bboxFilter == null)
        this._bboxFilter = bboxFilter;
      else
        this._bboxFilter = BoundingBox.intersect(bboxFilter, this._bboxFilter);
    } else {
      this._polyFilter = (Polygon)Geo.clip(this._polyFilter, bboxFilter);
      this._bboxFilter = new BoundingBox(this._polyFilter.getEnvelopeInternal());
    }
    return this;
  }

  /**
   * Set the area of interest to the given polygon.
   * Only objects inside or clipped by this polygon will be passed on to the analysis' `mapper` function.
   *
   * @param polygonFilter the bounding box to query the data in
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  public MapReducer<T> areaOfInterest(Polygon polygonFilter) {
    if (this._polyFilter == null) {
      if (this._bboxFilter == null)
        this._polyFilter = polygonFilter;
      else
        this._polyFilter = (Polygon)Geo.clip(polygonFilter, this._bboxFilter);
    } else {
      this._polyFilter = (Polygon)Geo.clip(polygonFilter, this._polyFilter);
    }
    this._bboxFilter = new BoundingBox(this._polyFilter.getEnvelopeInternal());
    return this;
  }

  /**
   * Set the timestamps for which to perform the analysis.
   * Depending on the *View*, this has slightly different semantics:
   * * For the OSMEntitySnapshotView it will set the time slices at which to take the "snapshots"
   * * For the OSMContributionView it will set the time interval in which to look for osm contributions (only the first and last
   *   timestamp of this list are contributing). Additionally, the timestamps are used in some of the `*aggregateByTimestamps` functions.
   *
   * @param tstamps the timestamps to do the analysis for
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  public MapReducer<T> timestamps(OSHDBTimestamps tstamps) {
    this._tstamps = tstamps;
    return this;
  }

  /**
   * Limits the analysis to the given osm entity types.
   *
   * @param typeFilter the set of osm types to filter (e.g. `EnumSet.of(OSMType.WAY)`)
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  public MapReducer<T> osmTypes(EnumSet<OSMType> typeFilter) {
    this._typeFilter = typeFilter;
    return this;
  }

  /**
   * Limits the analysis to the given osm entity types.
   *
   * @param type1 the set of osm types to filter (e.g. `OSMType.NODE`)
   * @param otherTypes more osm types which should be analyzed
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  public MapReducer<T> osmTypes(OSMType type1, OSMType ...otherTypes) {
    return this.osmTypes(EnumSet.of(type1, otherTypes));
  }

  /**
   * Adds a custom arbitrary filter that gets executed for each osm entity and determines if it should be considered for this analyis or not.
   *
   * @param f the filter function to call for each osm entity
   * @return `this` mapReducer (can be used to chain multiple commands together)
   */
  public MapReducer<T> filter(SerializablePredicate<OSMEntity> f) {
    this._filters.add(f);
    return this;
  }

  /**
   * Adds an osm tag filter: The analysis will be restricted to osm entities that have this tag key (with an arbitrary value).
   *
   * @param key the tag key to filter the osm entities for
   * @return `this` mapReducer (can be used to chain multiple commands together)
   * @throws Exception
   */
  public MapReducer<T> filterByTagKey(String key) throws Exception {
    int keyId = this.getTagKeyId(key);
    this._preFilters.add(oshEntitiy -> oshEntitiy.hasTagKey(keyId));
    this._filters.add(osmEntity -> osmEntity.hasTagKey(keyId));
    return this;
  }


  /**
   * Adds an osm tag filter: The analysis will be restricted to osm entities that have this tag key and value.
   *
   * @param key the tag key to filter the osm entities for
   * @param value the tag value to filter the osm entities for
   * @return `this` mapReducer (can be used to chain multiple commands together)
   * @throws Exception
   */
  public MapReducer<T> filterByTagValue(String key, String value) throws Exception {
    Pair<Integer, Integer> keyValueId = this.getTagValueId(key, value);
    int keyId = keyValueId.getKey();
    int valueId = keyValueId.getValue();
    this._filters.add(osmEntity -> osmEntity.hasTagValue(keyId, valueId));
    return this;
  }

  // some helper methods for internal use in the mapReduce functions

  // Helper that chains multiple oshEntity filters together
  private SerializablePredicate<OSHEntity> _getPreFilter() {
    return (this._preFilters.isEmpty()) ? (oshEntity -> true) : (oshEntity -> {
      for (SerializablePredicate<OSHEntity> filter : this._preFilters)
        if (!filter.test(oshEntity))
          return false;
      return true;
    });
  }

  // Helper that chains multiple osmEntity filters together
  private SerializablePredicate<OSMEntity> _getFilter() {
    return (this._filters.isEmpty()) ? (osmEntity -> true) : (osmEntity -> {
      for (SerializablePredicate<OSMEntity> filter : this._filters)
        if (!filter.test(osmEntity))
          return false;
      return true;
    });
  }

  // get all cell ids covered by the current area of interest's bounding box
  private Iterable<CellId> _getCellIds() {
    XYGridTree grid = new XYGridTree(OSHDB.MAXZOOM);
    if (this._bboxFilter == null || (this._bboxFilter.minLon >= this._bboxFilter.maxLon || this._bboxFilter.minLat >= this._bboxFilter.maxLat)) {
      // return an empty iterable if bbox is not set or empty
      System.err.println("warning: area of interest not set or empty");
      return Collections.emptyList();
    }
    return grid.bbox2CellIds(this._bboxFilter, true);
  }

  // get lists of timestamps from the OSHTimestamps object
  private List<Long> _getTimestamps() {
    return this._tstamps.getTimestamps();
  }

  // generic map-reduce functions (need to be implemented by the actual db/processing backend!

  protected <R, S> S mapReduceCellsOSMContribution(Iterable<CellId> cellIds, List<Long> tstamps, BoundingBox bbox, Polygon poly, SerializablePredicate<OSHEntity> preFilter, SerializablePredicate<OSMEntity> filter, SerializableFunction<OSMContribution, R> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    throw new UnsupportedOperationException("Reduce function not yet implemented");
  }
  protected <R, S> S flatMapReduceCellsOSMContributionGroupedById(Iterable<CellId> cellIds, List<Long> tstamps, BoundingBox bbox, Polygon poly, SerializablePredicate<OSHEntity> preFilter, SerializablePredicate<OSMEntity> filter, SerializableFunction<List<OSMContribution>, List<R>> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    throw new UnsupportedOperationException("Reduce function not yet implemented");
  }

  protected <R, S> S mapReduceCellsOSMEntitySnapshot(Iterable<CellId> cellIds, List<Long> tstamps, BoundingBox bbox, Polygon poly, SerializablePredicate<OSHEntity> preFilter, SerializablePredicate<OSMEntity> filter, SerializableFunction<OSMEntitySnapshot, R> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    throw new UnsupportedOperationException("Reduce function not yet implemented");
  }
  protected <R, S> S flatMapReduceCellsOSMEntitySnapshotGroupedById(Iterable<CellId> cellIds, List<Long> tstamps, BoundingBox bbox, Polygon poly, SerializablePredicate<OSHEntity> preFilter, SerializablePredicate<OSMEntity> filter, SerializableFunction<List<OSMEntitySnapshot>, List<R>> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    throw new UnsupportedOperationException("Reduce function not yet implemented");
  }

  // exposed map-reduce and map-aggregate functions, to be used by experienced users of the api

  public <R, S> S mapReduce(SerializableFunction<T, R> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    if (this._forClass.equals(OSMContribution.class)) {
      return this.mapReduceCellsOSMContribution(this._getCellIds(), this._getTimestamps(), this._bboxFilter, this._polyFilter, this._getPreFilter(), this._getFilter(), (SerializableFunction<OSMContribution, R>) mapper, identitySupplier, accumulator, combiner);
    } else if (this._forClass.equals(OSMEntitySnapshot.class)) {
      return this.mapReduceCellsOSMEntitySnapshot(this._getCellIds(), this._getTimestamps(), this._bboxFilter, this._polyFilter, this._getPreFilter(), this._getFilter(), (SerializableFunction<OSMEntitySnapshot, R>) mapper, identitySupplier, accumulator, combiner);
    } else throw new UnsupportedOperationException("No mapper implemented for your database type");
  }

  public <R, S> S flatMapReduceGroupedById(SerializableFunction<List<T>, List<R>> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    if (this._forClass.equals(OSMContribution.class)) {
      return this.flatMapReduceCellsOSMContributionGroupedById(this._getCellIds(), this._getTimestamps(), this._bboxFilter, this._polyFilter, this._getPreFilter(), this._getFilter(), (SerializableFunction<List<OSMContribution>, List<R>>) contributions -> mapper.apply((List<T>)contributions), identitySupplier, accumulator, combiner);
    } else if (this._forClass.equals(OSMEntitySnapshot.class)) {
      return this.flatMapReduceCellsOSMEntitySnapshotGroupedById(this._getCellIds(), this._getTimestamps(), this._bboxFilter, this._polyFilter, this._getPreFilter(), this._getFilter(), (SerializableFunction<List<OSMEntitySnapshot>, List<R>>) contributions -> mapper.apply((List<T>)contributions), identitySupplier, accumulator, combiner);
    } else throw new UnsupportedOperationException("No mapper implemented for your database type");
  }
  public <R, S> S flatMapReduce(SerializableFunction<T, List<R>> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    return this.flatMapReduceGroupedById(
        (List<T> inputList) -> {
          List<R> outputList = new LinkedList<>();
          inputList.stream().map(mapper).forEach(outputList::addAll);
          return outputList;
        },
        identitySupplier,
        accumulator,
        combiner
    );
  }

  public <R, S, U> SortedMap<U, S> mapAggregate(SerializableFunction<T, Pair<U, R>> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    return this.mapReduce(mapper, TreeMap::new, (SortedMap<U, S> m, Pair<U, R> r) -> {
      m.put(r.getKey(), accumulator.apply(m.getOrDefault(r.getKey(), identitySupplier.get()), r.getValue()));
      return m;
    }, (a,b) -> {
      SortedMap<U, S> combined = new TreeMap<>(a);
      for (SortedMap.Entry<U, S> entry: b.entrySet()) {
        combined.merge(entry.getKey(), entry.getValue(), combiner);
      }
      return combined;
    });
  }

  public <R, S, U> SortedMap<U, S> flatMapAggregateGroupedById(SerializableFunction<List<T>, List<Pair<U, R>>> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    return this.flatMapReduceGroupedById(mapper, TreeMap::new, (SortedMap<U, S> m, Pair<U, R> r) -> {
      m.put(r.getKey(), accumulator.apply(m.getOrDefault(r.getKey(), identitySupplier.get()), r.getValue()));
      return m;
    }, (a,b) -> {
      SortedMap<U, S> combined = new TreeMap<>(a);
      for (SortedMap.Entry<U, S> entry: b.entrySet()) {
        combined.merge(entry.getKey(), entry.getValue(), combiner);
      }
      return combined;
    });
  }

  public <R, S, U> SortedMap<U, S> flatMapAggregate(SerializableFunction<T, List<Pair<U, R>>> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    return this.flatMapAggregateGroupedById(
        inputList -> {
          List<Pair<U, R>> outputList = new LinkedList<>();
          inputList.stream().map(mapper).forEach(outputList::addAll);
          return outputList;
        },
        identitySupplier,
        accumulator,
        combiner
    );
  }

  public <R, S> SortedMap<OSHDBTimestamp, S> mapAggregateByTimestamp(SerializableFunction<T, R> mapper, SerializableSupplier<S> identitySupplier, SerializableBiFunction<S, R, S> accumulator, SerializableBinaryOperator<S> combiner) throws Exception {
    SortedMap<OSHDBTimestamp, S> result;
    List<OSHDBTimestamp> timestamps = this._getTimestamps().stream().map(OSHDBTimestamp::new).collect(Collectors.toList());
    if (this._forClass.equals(OSMContribution.class)) {
      result = this.mapAggregate(t -> {
        int timeBinIndex = Collections.binarySearch(timestamps, ((OSMContribution) t).getTimestamp());
        if (timeBinIndex < 0) { timeBinIndex = -timeBinIndex - 2; }
        return new ImmutablePair<>(timestamps.get(timeBinIndex), mapper.apply(t));
      }, identitySupplier, accumulator, combiner);
      timestamps.remove(timestamps.size()-1); // pop last element from timestamps list, so it doesn't get nodata-filled with "0" below
    } else if (this._forClass.equals(OSMEntitySnapshot.class)) {
      result = this.mapAggregate(t -> {
        return new ImmutablePair<>(((OSMEntitySnapshot) t).getTimestamp(), mapper.apply(t));
      }, identitySupplier, accumulator, combiner);
    } else throw new UnsupportedOperationException("mapAggregateByTimestamp only allowed for OSMContribution and OSMEntitySnapshot");
    // fill nodata entries with "0"
    timestamps.forEach(ts -> result.putIfAbsent(ts, identitySupplier.get()));
    return result;
  }

  // default helper methods to use the mapreducer's functionality more easily
  // like sum, weight, average, uniq, etc.

  public <R extends Number> SortedMap<OSHDBTimestamp, R> sumAggregateByTimestamp(SerializableFunction<T, R> mapper) throws Exception {
    return this.mapAggregateByTimestamp(mapper, () -> (R) (Integer) 0, (SerializableBiFunction<R, R, R>)(x,y) -> NumberUtils.add(x,y), (x,y) -> NumberUtils.add(x,y));
  }
  public SortedMap<OSHDBTimestamp, Integer> countAggregateByTimestamp() throws Exception {
    return this.sumAggregateByTimestamp(ignored -> 1);
  }
  public <R extends Number> SortedMap<OSHDBTimestamp, Double> averageAggregateByTimestamp(SerializableFunction<T, R> mapper) throws Exception {
    return this.mapAggregateByTimestamp(
        mapper,
        () -> new PayloadWithWeight<>((R) (Double) 0.0,0),
        (acc, cur) -> {
          acc.num = NumberUtils.add(acc.num, cur);
          acc.weight += 1;
          return acc;
        },
        (a, b) -> new PayloadWithWeight<>(NumberUtils.add(a.num, b.num), a.weight+b.weight)
    ).entrySet().stream().collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue().num.doubleValue() / e.getValue().weight,
        (v1, v2) -> v1,
        TreeMap::new
    ));
  }
  public <R extends Number> SortedMap<OSHDBTimestamp, Double> weightedAverageAggregateByTimestamp(SerializableFunction<T, WeightedValue<R>> mapper) throws Exception {
    return this.mapAggregateByTimestamp(
        mapper,
        () -> new PayloadWithWeight<>((R) (Double) 0.0,0),
        (acc, cur) -> {
          acc.num = NumberUtils.add(acc.num, cur.getValue());
          acc.weight += cur.getWeight();
          return acc;
        },
        (a, b) -> new PayloadWithWeight<>(NumberUtils.add(a.num, b.num), a.weight+b.weight)
    ).entrySet().stream().collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue().num.doubleValue() / e.getValue().weight,
        (v1, v2) -> v1,
        TreeMap::new
    ));
  }
  public <R> SortedMap<OSHDBTimestamp, Set<R>> uniqAggregateByTimestamp(SerializableFunction<T, R> mapper) throws Exception {
    return this.mapAggregateByTimestamp(
        mapper,
        HashSet<R>::new,
        (acc, cur) -> {
          acc.add(cur);
          return acc;
        },
        (set1, set2) -> {
          Set<R> combinedSets = new HashSet<R>(set1);
          combinedSets.addAll(set2);
          return combinedSets;
        }
    );
  }

  public <R extends Number, U> SortedMap<U, R> sumAggregate(SerializableFunction<T, Pair<U, R>> mapper) throws Exception {
    return this.mapAggregate(mapper, () -> (R) (Integer) 0, (SerializableBiFunction<R, R, R>)(x,y) -> NumberUtils.add(x,y), (x,y) -> NumberUtils.add(x,y));
  }
  public <U> SortedMap<U, Integer> countAggregate(SerializableFunction<T, U> mapper) throws Exception {
    return this.sumAggregate(data -> new ImmutablePair<U, Integer>(mapper.apply(data), 1));
  }
  public <R extends Number, U> SortedMap<U, Double> averageAggregate(SerializableFunction<T, Pair<U, R>> mapper) throws Exception {
    return this.mapAggregate(
        mapper,
        () -> new PayloadWithWeight<>((R) (Double) 0.0,0),
        (acc, cur) -> {
          acc.num = NumberUtils.add(acc.num, cur);
          acc.weight += 1;
          return acc;
        },
        (a, b) -> new PayloadWithWeight<>(NumberUtils.add(a.num, b.num), a.weight+b.weight)
    ).entrySet().stream().collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue().num.doubleValue() / e.getValue().weight,
        (v1, v2) -> v1,
        TreeMap::new
    ));
  }

  public <R extends Number, U> SortedMap<U, Double> weightedAverageAggregate(SerializableFunction<T, Pair<U, WeightedValue<R>>> mapper) throws Exception {
    return this.mapAggregate(
        mapper,
        () -> new PayloadWithWeight<>((R) (Double) 0.0, 0),
        (acc, cur) -> {
          acc.num = NumberUtils.add(acc.num, cur.getValue());
          acc.weight += cur.getWeight();
          return acc;
        },
        (a, b) -> new PayloadWithWeight<>(NumberUtils.add(a.num, b.num), a.weight + b.weight)
    ).entrySet().stream().collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue().num.doubleValue() / e.getValue().weight,
        (v1, v2) -> v1,
        TreeMap::new
    ));
  }
  public <R, U> SortedMap<U, Set<R>> uniqAggregate(SerializableFunction<T, Pair<U, R>> mapper) throws Exception {
    return this.mapAggregate(
        mapper,
        HashSet<R>::new,
        (acc, cur) -> {
          acc.add(cur);
          return acc;
        },
        (set1, set2) -> {
          Set<R> combinedSets = new HashSet<R>(set1);
          combinedSets.addAll(set2);
          return combinedSets;
        }
    );
  }

  public <R extends Number> R sum(SerializableFunction<T, R> mapper) throws Exception {
    return this.mapReduce(mapper, () -> (R) (Integer) 0, (SerializableBiFunction<R, R, R>)(x,y) -> NumberUtils.add(x,y), (SerializableBinaryOperator<R>)(x,y) -> NumberUtils.add(x,y));
  }
  public Integer count() throws Exception {
    return this.sum(ignored -> 1);
  }
  public <R> Set<R> uniq(SerializableFunction<T, R> mapper) throws Exception {
    return this.uniqAggregate(data -> new ImmutablePair<>(0, mapper.apply(data))).getOrDefault(0, new HashSet<>());
  }

  // auxiliary classes and interfaces

  // interfaces of some generic lambda functions used here, to make them serializable
  public interface SerializableSupplier<R> extends Supplier<R>, Serializable {}
  public interface SerializablePredicate<T> extends Predicate<T>, Serializable {}
  public interface SerializableBinaryOperator<T> extends BinaryOperator<T>, Serializable {}
  public interface SerializableFunction<T, R> extends Function<T, R>, Serializable {}
  public interface SerializableBiFunction<T1, T2, R> extends BiFunction<T1, T2, R>, Serializable {}

  /**
   * Immutable object that stores a numeric value and an associated weight.
   * Used to specify data input for the calculation of weighted averages.
   * @param <X> A numeric data type for the value.
   */
  public static class WeightedValue<X extends Number> {
    private X value;
    private double weight;
    
    public WeightedValue(X value, double weight) {
      this.value = value;
      this.weight = weight;
    }

    /**
     * @return the stored numeric value
     */
    public X getValue() {
      return value;
    }

    /**
     * @return the value's associated weight
     */
    public double getWeight() {
      return weight;
    }
  }

  // mutable version of WeightedValue type (for internal use to do faster aggregation)
  private class PayloadWithWeight<X> {
    X num;
    double weight;
    PayloadWithWeight(X num, double weight) {
      this.num = num;
      this.weight = weight;
    }
  }
}
