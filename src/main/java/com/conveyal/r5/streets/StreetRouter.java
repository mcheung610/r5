package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.util.TIntObjectHashMultimap;
import com.conveyal.r5.util.TIntObjectMultimap;
import gnu.trove.iterator.TIntIterator;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.apache.commons.math3.util.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * This routes over the street layer of a TransitNetwork.
 * It is a throw-away calculator object that retains routing state and after the search is finished.
 * Additional functions are called to retrieve the routing results from that state.
 */
public class StreetRouter {

    private static final Logger LOG = LoggerFactory.getLogger(StreetRouter.class);

    private static final boolean DEBUG_OUTPUT = false;

    public static final int ALL_VERTICES = -1;

    public final StreetLayer streetLayer;

    /**
     * True if this is transitStop search
     *
     * This will stop search when maxStop stops will be found
     * or before if queue is empty or time/distance limit is tripped
     */
    public boolean transitStopSearch = false;

    /**
     * non null if this is search for vertices with specific flags AKA bike share or Park ride
     *
     * This will stop the search when maxVertices are found
     * or before if queue is empty or time/distance limit is tripped
     */
    public VertexStore.VertexFlag flagSearch = null;

    /**
     * How many transit stops should we find
     */
    public int maxTransitStops = PointToPointQuery.MAX_ACCESS_STOPS;

    /**
     * How many vertices with flags should we find
     */
    public int maxVertices = 20;

    // TODO don't hardwire drive-on-right
    private TurnCostCalculator turnCostCalculator;

    // These are used for scaling coordinates in approximate distance calculations.
    // The lon value must be properly scaled to underestimate in the region where we're routing.
    private static final double MM_PER_DEGREE_LAT_FIXED =
            (SphericalDistanceLibrary.EARTH_CIRCUMFERENCE_METERS * 1000) / (360 * VertexStore.FIXED_FACTOR);
    private double millimetersPerDegreeLonFixed;
    private double maxSpeedSecondsPerMillimeter;

    /**
     * It uses all nonzero limit as a limit whichever gets hit first
     * For example if distanceLimitMeters &gt; 0 it is used as a limit. But if it isn't
     * timeLimitSeconds is used if it is bigger then 0. If both limits are 0 or both are set
     * warning is shown and both are used.
     */
    public int distanceLimitMeters = 0;
    public int timeLimitSeconds = 0;

    /** What routing variable (weight, distance, etc.) should be used in the dominance function? */
    public State.RoutingVariable dominanceVariable = State.RoutingVariable.WEIGHT;

    /**
     * Store the best state at the end of each edge. We store states at the ends of edges, rather than at vertices, so
     * that we can apply turn costs. You can't apply turn costs (which are vertex costs) when you are storing a single state
     * per vertex, because the vertex cost is not applied until leaving the vertex. This means that a state that must make
     * an expensive U-turn to reach the destination may beat out a state that is slightly less costly _at that vertex_ but
     * will complete the search with a cheap straight-through movement. We use the ends rather than the beginnings of edges
     * to avoid state proliferation (otherwise after traversing an edge you'd have to create states, many of which would
     * be dominated pretty quickly, for every outgoing edge at the to vertex).
     *
     * Storing states per edge is mathematically equivalent to creating a so-called edge-based graph in which all of the
     * street segments have been represented as nodes and all of the intersections/turn possibilities as edges, but that
     * is a very theoretical view and creates a semantic nightmare because it's hard to think about nodes that represent
     * things with dimension (not to mention never being sure whether you're talking about the original, standard graph
     * or the transformed, edge-based graph). We had a nightmarish time trying to keep this straight in OTP, and eventually
     * removed it. Using edge-based graphs to represent turn costs/restrictions is documented at http://geo.fsv.cvut.cz/gdata/2013/pin2/d/dokumentace/line_graph_teorie.pdf
     *
     * This would seem to obviate the need to have incomparable states at all, but it in fact does not, because of the existence
     * of complex turn restrictions that have via ways. This could be a simple U-turn on a dual carriageway, but could also be
     * something more complex (no right turn after left &c.). In these cases, we still have to have incomparable states when
     * we are partway through the restriction.
     *
     * When determining the weight at a vertex, one should just grab all the incoming edges, and take the minimum. However,
     * it's a bit more complicated to properly determine the time/weight at a split, because of turn costs. Suppose that
     * the best state at a particular vertex require a left turn onto the split edge; it is important to apply that left
     * turn costs. Even more important is to make sure that the split edge is not the end of a restricted turn; if it is,
     * one must reach the split via an alternate state.
     */
    // TODO we might be able to make this more efficient by taking advantage of the fact that we almost always have a
    // single state per edge (the only time we don't is when we're in the middle of a turn restriction).
    TIntObjectMultimap<State> bestStatesAtEdge = new TIntObjectHashMultimap<>();

    // The sort order of the priority queue uses the specified routing dominance variable.
    PriorityQueue<State> queue = new PriorityQueue<>(
            (s0, s1) -> (s0.getRoutingVariable(dominanceVariable) + s0.heuristic) -
                        (s1.getRoutingVariable(dominanceVariable) + s1.heuristic));

    // If you set this to a non-negative number, the search will be directed toward the vertex with that index.
    public int toVertex = ALL_VERTICES;

    /** Set individual properties here, or an entirely new request */
    public ProfileRequest profileRequest = new ProfileRequest();

    /**
     * Mode of transport used in this search. This router requires a single mode, so it is up to the caller to
     * disentangle the modes set in the profile request.
     */
    public StreetMode streetMode = StreetMode.WALK;

    // Allows supplying callbacks for monitoring search progress for debugging and visualization purposes.
    private RoutingVisitor routingVisitor;

    private Split originSplit;

    private Split destinationSplit;

    // The best known value of the chosen dominance variable at the destination, used to prune the search.
    private int bestValueAtDestination = Integer.MAX_VALUE;


    // This is maximal absolute latitude of origin, when there are multiple origin states
    // it is used when calculating A* goal direction heuristic
    private int maxAbsOriginLat = Integer.MIN_VALUE;


    /**
     * The preceding StreetRouter in a multi-router search.
     * For example if we are searching for P+R we need 2 street searches:
     * First driving from the origin to all car parks, then from all the car parks to nearby transit stops.
     * Recording the first street router in the second one allows reconstructing paths in the response.
     **/
    public StreetRouter previousRouter;

    /**
     * Supply a RoutingVisitor to track search progress for debugging.
     */
    public void setRoutingVisitor(RoutingVisitor routingVisitor) {
        this.routingVisitor = routingVisitor;
    }

    /**
     * Currently used for debugging snapping to vertices
     * TODO: API should probably be nicer
     * setOrigin on split or setOrigin that would return split
     */
    public Split getOriginSplit() {
        return originSplit;
    }

    /**
     * This uses stops found in {@link StopVisitor} if transitStopSearch is true
     * and DOESN'T search in found states for stops
     *
     * @return a map from transit stop indexes to their distances from the origin (or whatever the dominance variable is).
     * Note that the TransitLayer contains all the information about which street vertices are transit stops.
     */
    public TIntIntMap getReachedStops() {
        if (transitStopSearch && routingVisitor instanceof StopVisitor) {
            return ((StopVisitor) routingVisitor).getStops();
        }
        TIntIntMap result = new TIntIntHashMap();
        TransitLayer transitLayer = streetLayer.parentNetwork.transitLayer;
        transitLayer.stopForStreetVertex.forEachEntry((streetVertex, stop) -> {
            if (streetVertex == -1) return true;
            State state = getStateAtVertex(streetVertex);
            // TODO should this be time?
            if (state != null) result.put(stop, state.getRoutingVariable(dominanceVariable));
            return true; // continue iteration
        });
        return result;
    }

    /**
     * Return a map where the keys are all the reached vertices, and the values are their distances from the origin
     * (as used in the chosen dominance function).
     */
    public TIntIntMap getReachedVertices () {
        TIntIntMap result = new TIntIntHashMap();
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor();
        bestStatesAtEdge.forEachEntry((eidx, states) -> {
            if (eidx < 0) return true;

            State state = states.stream()
                    .reduce((s0, s1) -> s0.getRoutingVariable(dominanceVariable) < s1.getRoutingVariable(dominanceVariable) ? s0 : s1).get();
            e.seek(eidx);
            int vidx = e.getToVertex();

            if (!result.containsKey(vidx) || result.get(vidx) > state.getRoutingVariable(dominanceVariable))
                result.put(vidx, state.getRoutingVariable(dominanceVariable));

            return true; // continue iteration
        });
        return result;
    }

    /**
     * Uses states found in {@link VertexFlagVisitor} if flagSearch is for the same flag as requested one
     * and DOESN't search in found states for found vertices
     *
     * @return a map where all the keys are vertex indexes with the particular flag and all the values are states.
     */
    public TIntObjectMap<State> getReachedVertices (VertexStore.VertexFlag flag) {
        if (flagSearch == flag && routingVisitor instanceof VertexFlagVisitor) {
            return ((VertexFlagVisitor) routingVisitor).getVertices();
        }
        TIntObjectMap<State> result = new TIntObjectHashMap<>();
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor();
        VertexStore.Vertex v = streetLayer.vertexStore.getCursor();
        bestStatesAtEdge.forEachEntry((eidx, states) -> {
            if (eidx < 0) return true;

            State state = states.stream().reduce((s0, s1) ->
                    s0.getRoutingVariable(dominanceVariable) < s1.getRoutingVariable(dominanceVariable) ? s0 : s1).get();
            e.seek(eidx);
            int vidx = e.getToVertex();
            v.seek(vidx);

            if (v.getFlag(flag)) {
                if (!result.containsKey(vidx) || result.get(vidx).getRoutingVariable(dominanceVariable) >
                                                            state.getRoutingVariable(dominanceVariable)) {
                    result.put(vidx, state);
                }
            }

            return true; // continue iteration
        });
        return result;
    }

    public StreetRouter (StreetLayer streetLayer) {
        this.streetLayer = streetLayer;
        // TODO one of two things: 1) don't hardwire drive-on-right, or 2) https://en.wikipedia.org/wiki/Dagen_H
        this.turnCostCalculator = new TurnCostCalculator(streetLayer, true);
    }

    /**
     * Finds closest vertex which has streetMode permissions
     *
     * @param lat Latitude in floating point (not fixed int) degrees.
     * @param lon Longitude in flating point (not fixed int) degrees.
     * @return true if edge was found near wanted coordinate
     */
    public boolean setOrigin (double lat, double lon) {
        Split split = streetLayer.findSplit(lat, lon, StreetLayer.LINK_RADIUS_METERS, streetMode);
        if (split == null) {
            LOG.info("No street was found near the specified origin point of {}, {}.", lat, lon);
            return false;
        }
        originSplit = split;
        bestStatesAtEdge.clear();
        queue.clear();
        // from vertex is at end of back edge. Set edge correctly so that turn restrictions/costs are applied correctly
        // at the origin.
        State startState0 = new State(split.vertex0, split.edge + 1, streetMode);
        State startState1 = new State(split.vertex1, split.edge, streetMode);
        EdgeStore.Edge  edge = streetLayer.edgeStore.getCursor(split.edge);
        // Uses weight based on distance from end vertices and speed on edge which depends on transport mode
        float speedms = edge.calculateSpeed(profileRequest, streetMode);
        startState1.weight = (int) ((split.distance1_mm / 1000) / speedms);
        edge.advance();
        //speed can differ in different directions
        speedms = edge.calculateSpeed(profileRequest, streetMode);
        startState0.weight = (int) ((split.distance0_mm / 1000) / speedms);

        if (profileRequest.reverseSearch) {
             startState0.vertex = split.vertex1;
             startState1.vertex = split.vertex0;
        }

        //We need to set turn restrictions if turn restriction starts in first edge
        streetLayer.edgeStore.startTurnRestriction(streetMode, profileRequest.reverseSearch, startState0);
        streetLayer.edgeStore.startTurnRestriction(streetMode, profileRequest.reverseSearch, startState1);

        // NB not adding to bestStates, as it will be added when it comes out of the queue
        queue.add(startState0);
        queue.add(startState1);
        bestStatesAtEdge.put(startState0.backEdge, startState0);
        bestStatesAtEdge.put(startState1.backEdge, startState1);

        maxAbsOriginLat = originSplit.fixedLat;
        return true;
    }

    public void setOrigin (int fromVertex) {
        bestStatesAtEdge.clear();
        queue.clear();

        // sets maximal absolute origin latitude used for goal direction heuristic
        VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(fromVertex);
        maxAbsOriginLat = vertex.getFixedLat();

        // NB backEdge of -1 is no problem as it is a special case that indicates that the origin was a vertex.
        State startState = new State(fromVertex, -1, streetMode);
        queue.add(startState);
    }

    /**
     * Adds multiple origins.
     *
     * Each state is one origin. Weight, durationSeconds and distance is copied from state.
     * If legMode is LegMode.BICYCLE_RENT state.isBikeShare is set to true
     *
     * @param previousStates map of bikeshares/P+Rs vertexIndexes and states Return of {@link #getReachedVertices(VertexStore.VertexFlag)}}
     * @param switchTime How many s is added to state time (this is used when switching modes, renting bike, parking a car etc.)
     * @param switchCost This is added to the weight and is a cost of switching modes
     * @param legMode What origin search is this bike share or P+R
     */
    public void setOrigin(TIntObjectMap<State> previousStates, int switchTime, int switchCost, LegMode legMode) {
        bestStatesAtEdge.clear();
        queue.clear();
        //Maximal origin latitude is used in goal direction heuristic.
        final int[] maxOriginLatArr = { Integer.MIN_VALUE };

        previousStates.forEachEntry((vertexIdx, previousState) -> {
            // backEdge needs to be unique for each start state or they will wind up dominating each other.
            // subtract 1 from -vertexIdx because -0 == 0
            State state = new State(vertexIdx, previousState.backEdge, streetMode);
            state.weight = previousState.weight+switchCost;
            state.durationSeconds = previousState.durationSeconds;
            state.incrementTimeInSeconds(switchTime);
            if (legMode == LegMode.BICYCLE_RENT) {
                state.isBikeShare = true;
            }
            state.distance = previousState.distance;
            if (!isDominated(state)) {
                bestStatesAtEdge.put(state.backEdge, state);
                queue.add(state);
                VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(state.vertex);
                int deltaLatFixed = vertex.getFixedLat();
                maxOriginLatArr[0] = Math.max(maxOriginLatArr[0], Math.abs(deltaLatFixed));
            }
            return true;
        });
        maxAbsOriginLat = maxOriginLatArr[0];

    }

    /**
     * Finds closest vertex which has streetMode permissions
     *
     * @param lat Latitude in floating point (not fixed int) degrees.
     * @param lon Longitude in flating point (not fixed int) degrees.
     * @return true if edge was found near wanted coordinate
     */
    public boolean setDestination (double lat, double lon) {
        this.destinationSplit = streetLayer.findSplit(lat, lon, StreetLayer.LINK_RADIUS_METERS, streetMode);
        return this.destinationSplit != null;
    }

    public void setDestination (Split split) {
        this.destinationSplit = split;
    }

    /**
     * Call one of the setOrigin functions first before calling route().
     *
     * It uses all nonzero limit as a limit whichever gets hit first
     * For example if distanceLimitMeters &gt; 0 it is used a limit. But if it isn't
     * timeLimitSeconds is used if it is bigger then 0. If both limits are 0 or both are set
     * warning is shown and both are used.
     */
    public void route () {

        long startTime = System.currentTimeMillis();

        final int distanceLimitMm;
        //This is needed otherwise timeLimitSeconds gets changed and
        // on next call of route on same streetRouter wrong warnings are returned
        // (since timeLimitSeconds is MAX_INTEGER not 0)
        // FIXME this class is supposed to be throw-away, should we be reusing instances at all? change this variable name to be clearer.
        final int tmpTimeLimitSeconds;

        // Set up goal direction.
        if (destinationSplit != null) {
            // This search has a destination, so enable A* goal direction.
            // To speed up the distance calculations that are part of the A* heuristic, we precalculate some factors.
            // We want to scale X distances by the cosine of the higher of the two latitudes to underestimate distances,
            // as required for the A* heuristic to be admissible.
            // TODO this should really use the max latitude of the whole street layer.
            int maxAbsLatFixed = Math.max(Math.abs(destinationSplit.fixedLat), Math.abs(maxAbsOriginLat));
            double maxAbsLatRadians = Math.toRadians(VertexStore.fixedDegreesToFloating(maxAbsLatFixed));
            millimetersPerDegreeLonFixed = MM_PER_DEGREE_LAT_FIXED * Math.cos(maxAbsLatRadians);
            // FIXME account for speeds of individual street segments, not just speed in request
            double maxSpeedMetersPerSecond = profileRequest.getSpeed(streetMode);
            // Car speed is currently often unspecified in the request and defaults to zero.
            if (maxSpeedMetersPerSecond == 0) maxSpeedMetersPerSecond = 36.11; // 130 km/h
            maxSpeedSecondsPerMillimeter = 1 / (maxSpeedMetersPerSecond * 1000);
        }

        if (distanceLimitMeters > 0) {
            // Distance in State is in millimeters. Distance limit is in meters, requiring a conversion.
            distanceLimitMm = distanceLimitMeters * 1000;
            if (dominanceVariable != State.RoutingVariable.DISTANCE_MILLIMETERS) {
                LOG.warn("Setting a distance limit when distance is not the dominance function, this is a resource limiting issue and paths may be incorrect.");
            }
        } else {
            // There is no distance limit. Set it to the largest possible value to allow routing to progress.
            distanceLimitMm = Integer.MAX_VALUE;
        }

        if (timeLimitSeconds > 0) {
            tmpTimeLimitSeconds = timeLimitSeconds;
            if (dominanceVariable != State.RoutingVariable.DURATION_SECONDS) {
                LOG.warn("Setting a time limit when time is not the dominance function, this is a resource limiting issue and paths may be incorrect.");
            }
        } else {
            // There is no time limit. Set it to the largest possible value to allow routing to progress.
            tmpTimeLimitSeconds = Integer.MAX_VALUE;
        }

        if (timeLimitSeconds > 0 && distanceLimitMeters > 0) {
            LOG.warn("Both distance limit of {}m and time limit of {}s are set in StreetRouter", distanceLimitMeters, timeLimitSeconds);
        } else if (timeLimitSeconds == 0 && distanceLimitMeters == 0) {
            LOG.debug("Distance and time limit are both set to 0 in StreetRouter. This means NO LIMIT in searching so the entire street graph will be explored. This can be slow.");
        } else if (distanceLimitMeters > 0) {
            LOG.debug("Using distance limit of {} meters", distanceLimitMeters);
        } else if (timeLimitSeconds > 0) {
            LOG.debug("Using time limit of {} sec", timeLimitSeconds);
        }

        if (queue.size() == 0) {
            LOG.warn("Routing without first setting an origin, no search will happen.");
        }

        PrintStream debugPrintStream = null;
        if (DEBUG_OUTPUT) {
            File debugFile = new File(String.format("street-router-debug.csv"));
            OutputStream outputStream;
            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(debugFile));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            debugPrintStream = new PrintStream(outputStream);
            debugPrintStream.println("lat,lon,weight");
        }

        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();

        if (transitStopSearch) {
            routingVisitor = new StopVisitor(streetLayer, dominanceVariable, maxTransitStops, profileRequest.getMinTimeLimit(streetMode));
        } else if (flagSearch != null) {
            routingVisitor = new VertexFlagVisitor(streetLayer, dominanceVariable, flagSearch, maxVertices, profileRequest.getMinTimeLimit(streetMode));
        }
        while (!queue.isEmpty()) {
            State s0 = queue.poll();

            if (DEBUG_OUTPUT) {
                VertexStore.Vertex v = streetLayer.vertexStore.getCursor(s0.vertex);

                double lat = v.getLat();
                double lon = v.getLon();

                if (s0.backEdge != -1) {
                    EdgeStore.Edge e = streetLayer.edgeStore.getCursor(s0.backEdge);
                    v.seek(e.getFromVertex());
                    lat = (lat + v.getLat()) / 2;
                    lon = (lon + v.getLon()) / 2;
                }

                debugPrintStream.println(String.format("%.6f,%.6f,%d", v.getLat(), v.getLon(), s0.weight));
            }

            // The state coming off the priority queue may have been dominated by some other state that was produced
            // by traversing the same edge. Check that the state coming off the queue has not been dominated before
            // exploring it. States at the origin may have their backEdge set to a negative number to indicate that
            // they have no backEdge (were not produced by traversing an edge). Skip the check for those states.
            if (s0.backEdge >= 0 && !bestStatesAtEdge.get(s0.backEdge).contains(s0)) continue;

            // If the search has reached the destination, the state coming off the queue is the best way to get there.
            if (toVertex > 0 && toVertex == s0.vertex) break;

            // End the search if the state coming off the queue has exceeded the best-known cost to reach the destination.
            // TODO how important is this? How can this even happen? In a street search, is target pruning even effective?
            if (s0.getRoutingVariable(dominanceVariable) > bestValueAtDestination) break;

            // Hit RoutingVistor callbacks to monitor search progress.
            if (routingVisitor != null) {
                routingVisitor.visitVertex(s0);

                if (routingVisitor.shouldBreakSearch()) {
                    LOG.debug("{} routing visitor stopped search", routingVisitor.getClass().getSimpleName());
                    queue.clear();
                    break;
                }
            }

            // If this state is at the destination, figure out the cost at the destination and use it for target pruning.
            // TODO explain what "target pruning" is in this context and why we need it. It seems that this is mainly about traversing split streets.
            // By using getState(split) we include turn restrictions and turn costs.
            // We've already added this state to bestStates so getState will be correct.
            if (destinationSplit != null && (s0.vertex == destinationSplit.vertex0 || s0.vertex == destinationSplit.vertex1)) {
                State atDest = getState(destinationSplit);
                // atDest could be null even though we've found a nearby vertex because of a turn restriction
                if (atDest != null && bestValueAtDestination > atDest.getRoutingVariable(dominanceVariable)) {
                    bestValueAtDestination = atDest.getRoutingVariable(dominanceVariable);
                }
            }

            TIntList edgeList;
            if (profileRequest.reverseSearch) {
                edgeList = streetLayer.incomingEdges.get(s0.vertex);
            } else {
                edgeList = streetLayer.outgoingEdges.get(s0.vertex);
            }
            // explore edges leaving this vertex
            edgeList.forEach(eidx -> {
                edge.seek(eidx);
                State s1 = edge.traverse(s0, streetMode, profileRequest, turnCostCalculator);
                if (s1 != null && s1.distance <= distanceLimitMm && s1.getDurationSeconds() < tmpTimeLimitSeconds) {
                    if (!isDominated(s1)) {
                        // Calculate the heuristic (which involves a square root) only when the state is retained.
                        s1.heuristic = calcHeuristic(s1);
                        bestStatesAtEdge.put(s1.backEdge, s1);
                        queue.add(s1);
                    }
                }
                return true; // Iteration over the edge list should continue.
            });
        }
        if (DEBUG_OUTPUT) {
            debugPrintStream.close();
        }
        long routingTimeMsec = System.currentTimeMillis() - startTime;
        LOG.debug("Routing took {} msec", routingTimeMsec);
    }

    /**
     * Given a new state, check whether it is dominated by any existing state that resulted from traversing the
     * same edge. Side effect: Boot out any existing states that are dominated by the new one.
     */
    private boolean isDominated(State newState) {
        // States in turn restrictions are incomparable (don't dominate and aren't dominated by other states)
        // If the new state is not in a turn restriction, check whether it dominates any existing states and remove them.
        // Multimap returns empty list for missing keys.
        for (Iterator<State> it = bestStatesAtEdge.get(newState.backEdge).iterator(); it.hasNext(); ) {
            State existingState = it.next();
            if (dominates(existingState, newState)) {
                // If any existing state dominates the new one, bail out early and declare the new state dominated.
                // We want to check if the existing state dominates the new one before the other way around because
                // when states are equal, the existing one should win (and the special case for turn restrictions).
                return true;
            } else if (dominates(newState, existingState)) {
                it.remove();
            }
        }
        return false; // Nothing existing has dominated this new state: it's non-dominated.
    }

    /**
     * Provide an underestimate on the remaining distance/weight/time to the destination (the A* heuristic).
     */
    private int calcHeuristic (State state) {
        // If there's no destination, there's no goal direction. Zero is always a valid underestimate.
        if (destinationSplit == null) return 0;
        VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(state.vertex);
        int deltaLatFixed = destinationSplit.fixedLat - vertex.getFixedLat();
        int deltaLonFixed = destinationSplit.fixedLon - vertex.getFixedLon();
        double millimetersX = millimetersPerDegreeLonFixed * deltaLonFixed;
        double millimetersY = MM_PER_DEGREE_LAT_FIXED * deltaLatFixed;
        double distanceMillimeters = FastMath.sqrt(millimetersX * millimetersX + millimetersY * millimetersY);
        double estimate = distanceMillimeters;
        if (dominanceVariable != State.RoutingVariable.DISTANCE_MILLIMETERS) {
            // Calculate time in seconds to traverse this distance in a straight line.
            // Weight should always be greater than or equal to time in seconds.
            estimate *= maxSpeedSecondsPerMillimeter;
        }
        if (dominanceVariable == State.RoutingVariable.WEIGHT && streetMode == StreetMode.WALK) {
            estimate *= EdgeStore.WALK_RELUCTANCE_FACTOR;
        }
        return (int) estimate;
    }

    /**
     * @return true if s1 is better *or equal* to s2, otherwise return false.
     */
    private boolean dominates (State s1, State s2) {
        if (s1.turnRestrictions == null && s2.turnRestrictions == null) {
            // The simple case where neither state has turn restrictions.
            // Note this is <= rather than < because we want an existing state with the same weight to beat a new one.
            return s1.getRoutingVariable(dominanceVariable) <= s2.getRoutingVariable(dominanceVariable);
        }
        // At least one of the states has turn restrictions.
        // Generally, a state with turn restrictions cannot dominate another state and cannot be dominated.
        // However, if we make all turn-restricted states strictly incomparable we can get infinite loops with
        // adjacent turn restrictions, see #88.
        // So we make an exception that states with exactly the same turn restrictions dominate one another.
        // In practice, this means once we have a state with a certain set of turn restrictions, we don't allow any
        // more at the same location.
        if (s1.turnRestrictions != null && s2.turnRestrictions != null &&
            s1.turnRestrictions.size() == s2.turnRestrictions.size()) {
                boolean[] same = new boolean[]{true}; // Trick to circumvent java "effectively final" ridiculousness.
                s1.turnRestrictions.forEachEntry((ridx, pos) -> {
                    if (!s2.turnRestrictions.containsKey(ridx) || s2.turnRestrictions.get(ridx) != pos) same[0] = false;
                    return same[0]; // Continue iteration until a difference is discovered, then bail out.
                });
                if (same[0]) return true; // s1 dominates s2 because it has the same turn restrictions.
                // TODO shouldn't we add a test to see which one has the lower dominance variable, just to make this more principled?
                // As in: states are comparable only when they have the same set of turn restrictions.
        }
        // At least one of the states has turn restrictions. Neither dominates the other.
        return false;
    }

    /**
     * Get a single best state at the end of an edge.
     * There can be more than one state at the end of an edge due to turn restrictions
     */
    public State getStateAtEdge (int edgeIndex) {
        Collection<State> states = bestStatesAtEdge.get(edgeIndex);
        if (states.isEmpty()) {
            return null; // Unreachable
        }
        // Get the lowest weight, even if it's in the middle of a turn restriction.
        return states.stream().reduce((s0, s1) ->
                s0.getRoutingVariable(dominanceVariable) < s1.getRoutingVariable(dominanceVariable) ? s0 : s1).get();
    }

    /**
     * Get a single best state at a vertex. NB this should not be used for propagating to samples, as you need to apply
     * turn costs/restrictions during propagation.
     */
    public State getStateAtVertex (int vertexIndex) {
        State ret = null;

        TIntList edgeList;
        if (profileRequest.reverseSearch) {
            edgeList = streetLayer.outgoingEdges.get(vertexIndex);
        } else {
            edgeList = streetLayer.incomingEdges.get(vertexIndex);
        }

        for (TIntIterator it = edgeList.iterator(); it.hasNext();) {
            int eidx = it.next();

            State state = getStateAtEdge(eidx);

            if (state == null) continue;

            if (ret == null) ret = state;
            else if (ret.getRoutingVariable(dominanceVariable) > state.getRoutingVariable(dominanceVariable)) {
                ret = state;
            }
        }

        return ret;
    }

    public int getTravelTimeToVertex (int vertexIndex) {
        State state = getStateAtVertex(vertexIndex);
        return state != null ? state.durationSeconds : Integer.MAX_VALUE;
    }

    /**
     * Returns state with smaller weight to vertex0 or vertex1
     *
     * If state to only one vertex exists return that vertex.
     * If state to none of the vertices exists returns null
     * @param split
     * @return
     */
    public State getState(Split split) {
        // get all the states at all the vertices
        List<State> relevantStates = new ArrayList<>();

        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(split.edge);

        TIntList edgeList;
        if (profileRequest.reverseSearch) {
            edgeList = streetLayer.outgoingEdges.get(split.vertex1);
        } else {
            edgeList = streetLayer.incomingEdges.get(split.vertex0);
        }

        for (TIntIterator it = edgeList.iterator(); it.hasNext();) {
            Collection<State> states = bestStatesAtEdge.get(it.next());
            // NB this needs a state to copy turn restrictions into. We then don't use that state, which is fine because
            // we don't need the turn restrictions any more because we're at the end of the search
            states.stream().filter(s -> e.canTurnFrom(s, new State(-1, split.edge, s), profileRequest.reverseSearch))
                    .map(s -> {
                        State ret = new State(-1, split.edge, s);
                        ret.streetMode = s.streetMode;

                        // figure out the turn cost
                        int turnCost = this.turnCostCalculator.computeTurnCost(s.backEdge, split.edge, s.streetMode);
                        int traversalCost = (int) Math.round(split.distance0_mm / 1000d / e.calculateSpeed(profileRequest, s.streetMode));

                        // TODO length of perpendicular
                        ret.incrementWeight(turnCost + traversalCost);
                        ret.incrementTimeInSeconds(turnCost + traversalCost);
                        ret.distance += split.distance0_mm;

                        return ret;
                    })
                    .forEach(relevantStates::add);
        }

        // advance to back edge
        e.advance();

        if (profileRequest.reverseSearch) {
            edgeList = streetLayer.outgoingEdges.get(split.vertex0);
        } else {
            edgeList = streetLayer.incomingEdges.get(split.vertex1);
        }

        for (TIntIterator it = edgeList.iterator(); it.hasNext();) {
            Collection<State> states = bestStatesAtEdge.get(it.next());
            states.stream().filter(s -> e.canTurnFrom(s, new State(-1, split.edge + 1, s), profileRequest.reverseSearch))
                    .map(s -> {
                        State ret = new State(-1, split.edge + 1, s);
                        ret.streetMode = s.streetMode;

                        // figure out the turn cost
                        int turnCost = this.turnCostCalculator.computeTurnCost(s.backEdge, split.edge + 1, s.streetMode);
                        int traversalCost = (int) Math.round(split.distance1_mm / 1000d / e.calculateSpeed(profileRequest, s.streetMode));
                        ret.distance += split.distance1_mm;

                        // TODO length of perpendicular
                        ret.incrementWeight(turnCost + traversalCost);
                        ret.incrementTimeInSeconds(turnCost + traversalCost);

                        return ret;
                    })
                    .forEach(relevantStates::add);
        }

        return relevantStates.stream()
                .reduce((s0, s1) -> s0.getRoutingVariable(dominanceVariable) < s1.getRoutingVariable(dominanceVariable) ? s0 : s1)
                .orElse(null);
    }

    public Split getDestinationSplit() {
        return destinationSplit;
    }

    /**
     * Given the geographic coordinates of a starting point...
     * Returns the State with the smaller weight to vertex0 or vertex1
     * TODO explain what this is for.
     *
     * First split is called with streetMode Mode
     *
     * If state to only one vertex exists return that vertex.
     * If state to none of the vertices exists returns null
     * @return
     */
    public State getState(double lat, double lon) {
        Split split = streetLayer.findSplit(lat, lon, StreetLayer.LINK_RADIUS_METERS, streetMode);
        if (split == null) {
            LOG.info("No street was found near the specified origin point of {}, {}.", lat, lon);
            return null;
        }
        return getState(split);
    }

    public static class State implements Cloneable,Serializable {
        public int vertex;
        public int weight;
        public int backEdge;

        //In simple search both those variables have same values
        //But in complex search (P+R, Bike share) first variable have duration of all the legs
        //and second, duration only in this leg
        //this is used for limiting search time in VertexFlagVisitor
        protected int durationSeconds;
        protected int durationFromOriginSeconds;
        //Distance in mm
        public int distance;
        public int idx;
        public StreetMode streetMode;
        public State backState; // previous state in the path chain
        public boolean isBikeShare = false; //is true if vertex in this state is Bike sharing station where mode switching occurs
        public int heuristic = 0; // Underestimate of remaining weight to the destination.

        /**
         * All turn restrictions this state is currently passing through.
         * The values are how many edges of a turn restriction have been traversed so far,
         * keyed on the turn restriction index.
         * If the value is 1 we have traversed only the from edge, etc.
         */
        public TIntIntMap turnRestrictions;

        public State(int atVertex, int viaEdge, State backState) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            //Note here it can happen that back state has edge with negative index
            //This means that this state was created from vertex and can be skipped in display
            //but it is necessary in bike sharing and P+R to combine WALK and BIKE/CAR parts+
            this.backState = backState;
            this.distance = backState.distance;
            this.durationSeconds = backState.durationSeconds;
            this.durationFromOriginSeconds = backState.durationFromOriginSeconds;
            this.weight = backState.weight;
            this.idx = backState.idx+1;
        }

        public State(int atVertex, int viaEdge, StreetMode streetMode) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = null;
            this.distance = 0;
            this.streetMode = streetMode;
            this.durationSeconds = 0;
            this.durationFromOriginSeconds = 0;
            this.idx = 0;
        }

        protected State clone() {
            State ret;
            try {
                ret = (State) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException("This is not happening");
            }
            return ret;
        }

        /**
         * Reverses order of states in arriveBy=true searches. Because start and target are reversed there
         * @param transportNetwork this is used for getting from/to vertex in backEdge
         * @return last edge in reversed order
         */
        public State reverse(TransportNetwork transportNetwork) {
            State orig = this;
            State ret = orig.reversedClone();
            int edge = -1;
            while (orig.backState != null) {
                //LOG.info("START ORIG:{} RET:{}", orig, ret);
                edge = orig.backEdge;
                State child = ret.clone();
                child.backState = ret;
                child.backEdge = edge;
                boolean traversingBackward = false;
                EdgeStore.Edge origBackEdge = transportNetwork.streetLayer.edgeStore.getCursor(orig.backEdge);
                if (origBackEdge.getFromVertex() == origBackEdge.getToVertex()
                    && ret.vertex == origBackEdge.getFromVertex()) {
                    traversingBackward = true;
                    child.vertex = origBackEdge.getToVertex();
                    //LOG.info("Case 1");
                } else if (ret.vertex == origBackEdge.getFromVertex()) {
                    child.vertex = origBackEdge.getToVertex();
                    traversingBackward = false;
                    //LOG.info("Case 2");
                }else if (ret.vertex == origBackEdge.getToVertex()) {
                    child.vertex = origBackEdge.getFromVertex();
                    traversingBackward = true;
                    //LOG.info("Case 3");
                }
                //LOG.info("State idx:{} tra:{}", orig.idx, traversingBackward);
                /*
                if (traversingBackward != ret.getOptions().arriveBy) {
                    LOG.error("Actual traversal direction does not match traversal direction in TraverseOptions.");
                    //defectiveTraversal = true;
                }*/
                child.incrementWeight(orig.weight-orig.backState.weight);
                child.durationSeconds += orig.durationSeconds - orig.backState.durationSeconds;
                if (orig.backState != null) {
                    child.distance += Math.abs(orig.distance-orig.backState.distance);
                }
                child.streetMode = orig.streetMode;
                //LOG.info("CHILD:{}", child);
                ret = child;
                orig = orig.backState;
            }
            return ret;
        }

        public State reversedClone() {
            State newState = new State(this.vertex, -1, this.streetMode);
            newState.idx = idx;
            return newState;
        }

        public void incrementTimeInSeconds(long seconds) {
            if (seconds < 0) {
                LOG.warn("A state's time is being incremented by a negative amount while traversing edge "
                    );
                //defectiveTraversal = true;
                return;
            }
/*
            durationSeconds += seconds;
            time += seconds;
*/
            //TODO: decrease time
            if (false) {

                durationSeconds-=seconds;
                durationFromOriginSeconds -= seconds;
            } else {

                durationSeconds+=seconds;
                durationFromOriginSeconds += seconds;
            }


        }

        public int getDurationSeconds() {
            return durationSeconds;
        }

        public void incrementWeight(float weight) {
            this.weight+=(int)weight;
        }

        public String dump() {
            State state = this;
            StringBuilder out = new StringBuilder();
            out.append("BEGIN PATH DUMP\n");
            while (state != null) {
                out.append(String.format("%s at %s via %s\n", state.vertex, state.weight, state.backEdge));
                state = state.backState;
            }
            out.append("END PATH DUMP\n");

            return out.toString();
        }

        public String compactDump(boolean reverse) {
            State state = this;
            StringBuilder out = new StringBuilder();
            String middle;
            if (reverse) {
                middle = "->";
            } else {
                middle = "<-";
            }
            while (state != null) {
                out.append(String.format("%s %d ",middle, state.backEdge));
                state = state.backState;
            }
            return out.toString();
        }


        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("State{");
            sb.append("vertex=").append(vertex);
            sb.append(", weight=").append(weight);
            sb.append(", backEdge=").append(backEdge);
            sb.append(", durationSeconds=").append(durationSeconds);
            sb.append(", distance=").append(distance);
            sb.append(", idx=").append(idx);
            sb.append('}');
            return sb.toString();
        }

        public int getRoutingVariable (RoutingVariable variable) {
            if (variable == null) throw new NullPointerException("Routing variable is null");

            switch (variable) {
                case DURATION_SECONDS:
                    return this.durationSeconds;
                case WEIGHT:
                    return this.weight;
                case DISTANCE_MILLIMETERS:
                    return this.distance;
                default:
                    throw new IllegalStateException("Unknown routing variable");
            }
        }

        public static enum RoutingVariable {
            /** Time, in seconds */
            DURATION_SECONDS,

            /** Weight/generalized cost (this is what is actually used to find paths) */
            WEIGHT,

            /** Distance, in millimeters */
            DISTANCE_MILLIMETERS
        }
    }

    /**
     * Saves maxStops number of transitStops that are at least minTravelTimeSeconds from start of search
     * If stop is found multiple times best states according to dominanceVariable wins.
     */
    private static class StopVisitor implements RoutingVisitor {
        private final int minTravelTimeSeconds;

        private final StreetLayer streetLayer;

        private final State.RoutingVariable dominanceVariable;

        private final int maxStops;

        private final int NO_STOP_FOUND;

        TIntIntMap stops = new TIntIntHashMap();

        /**
         * @param streetLayer          needed because we need stopForStreetVertex
         * @param dominanceVariable    according to which dominance variable should states be compared (same as in routing)
         * @param maxStops             maximal number of stops that should be found
         * @param minTravelTimeSeconds for stops that should be still added to list of stops
         */
        public StopVisitor(StreetLayer streetLayer, State.RoutingVariable dominanceVariable,
            int maxStops, int minTravelTimeSeconds) {
            this.minTravelTimeSeconds = minTravelTimeSeconds;
            this.streetLayer = streetLayer;
            this.dominanceVariable = dominanceVariable;
            this.maxStops = maxStops;
            this.NO_STOP_FOUND = streetLayer.parentNetwork.transitLayer.stopForStreetVertex
                .getNoEntryKey();
        }

        /**
         * If vertex at current state is transit stop. It adds it to best stops
         * if it is more then minTravelTimeSeconds away and is better then existing path
         * to the same stop according to dominance variable
         */
        @Override
        public void visitVertex(State state) {
            int stop = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.get(state.vertex);
            if (stop != NO_STOP_FOUND) {
                if (state.durationSeconds < minTravelTimeSeconds) {
                    return;
                }
                if (!stops.containsKey(stop) || stops.get(stop) > state
                    .getRoutingVariable(dominanceVariable)) {
                    stops.put(stop, state.getRoutingVariable(dominanceVariable));
                }
            }

        }

        /**
         * @return true when maxStops transitStops are found
         */
        public boolean shouldBreakSearch() {
            return stops.size() >= this.maxStops;
        }

        /**
         * @return found stops. Same format of returned value as in {@link StreetRouter#getReachedStops()}
         */
        public TIntIntMap getStops() {
            return stops;
        }
    }

    /**
     * Saves maxVertices number of vertices which have wantedFlag
     * and are at least minTravelTimeSeconds away from the origin
     * <p>
     * If vertex is found multiple times vertex with lower dominanceVariable is saved
     */
    private static class VertexFlagVisitor implements RoutingVisitor {
        private final int minTravelTimeSeconds;

        private final State.RoutingVariable dominanceVariable;

        private final int maxVertices;

        private final VertexStore.VertexFlag wantedFlag;

        VertexStore.Vertex v;

        TIntObjectMap<State> vertices = new TIntObjectHashMap<>();

        //Save vertices which are too close so that if they appear again (with longer path to them)
        // they are also skipped 
        TIntSet skippedVertices = new TIntHashSet();

        public VertexFlagVisitor(StreetLayer streetLayer, State.RoutingVariable dominanceVariable,
            VertexStore.VertexFlag wantedFlag, int maxVertices, int minTravelTimeSeconds) {
            this.minTravelTimeSeconds = minTravelTimeSeconds;
            this.dominanceVariable = dominanceVariable;
            this.wantedFlag = wantedFlag;
            this.maxVertices = maxVertices;
            v = streetLayer.vertexStore.getCursor();
        }

        /**
         * If vertex at current state has wantedFlag it is added to vertices map
         * if it is more then minTravelTimeSeconds away and has backState and non negative vertexIdx
         * <p>
         * If vertex is found multiple times vertex with lower dominanceVariable is saved
         *
         * @param state
         */
        @Override
        public void visitVertex(State state) {
            if (state.vertex < 0 ||
                //skips origin states for bikeShare (since in cycle search for bikeShare origin states
                //can be added to vertices otherwise since they could be traveled for minTravelTimeSeconds with different transport mode)
                state.backState == null || state.durationFromOriginSeconds < minTravelTimeSeconds ||
                skippedVertices.contains(state.vertex)
                ) {
                // Make sure that vertex to which you can come sooner then minTravelTimeSeconds won't be used
                // if a path which uses more then minTravelTimeSeconds is found
                // since this means we need to walk/cycle/drive longer then required
                if (state.vertex > 0 && state.durationFromOriginSeconds < minTravelTimeSeconds) {
                    skippedVertices.add(state.vertex);
                }
                return;
            }
            v.seek(state.vertex);
            if (v.getFlag(wantedFlag)) {
                if (!vertices.containsKey(state.vertex)
                    || vertices.get(state.vertex).getRoutingVariable(dominanceVariable) > state
                    .getRoutingVariable(dominanceVariable)) {
                    vertices.put(state.vertex, state);
                }
            }

        }

        /**
         * @return true when maxVertices vertices are found
         */
        public boolean shouldBreakSearch() {
            return vertices.size() >= this.maxVertices;
        }

        /**
         * @return found vertices with wantedFlag. Same format of returned value as in {@link StreetRouter#getReachedVertices(VertexStore.VertexFlag)}
         */
        public TIntObjectMap<State> getVertices() {
            return vertices;
        }
    }
}
