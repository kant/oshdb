package org.heigit.bigspatialdata.oshdb.api.mapreducer;

import org.heigit.bigspatialdata.oshdb.api.generic.lambdas.SerializableFunction;

/**
 * Interface for MapReducers or MapAggregators that can be (further) aggregated by an arbitrary index
 *
 * @param <M> the resulting class of the aggregateBy operation
 * @param <X> the data type the index function is supplied with
 */
interface MapAggregatable<M, X> {
  /**
   * Sets a custom aggregation function that is used to (further) group output results into.
   *
   * @param indexer a function that will be called for each input element and returns a value that will be used to group the results by
   * @param <U> the data type of the values used to aggregate the output. has to be a comparable type
   * @return a MapAggregator object with the equivalent state (settings, filters, map function, etc.) of the current MapReducer object
   */
  <U extends Comparable> M aggregateBy(SerializableFunction<X, U> indexer);
}
