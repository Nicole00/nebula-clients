/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License,
 * attached with Common Clause Condition 1.0, found in the LICENSES directory.
 */

package com.vesoft.nebula.client.graph.storage.data;

public class Edge {
    private final String srcId;
    private final String dstId;
    private final EdgeType edgeType;

    public Edge(String srcId, String dstId, EdgeType edgeType) {
        this.srcId = srcId;
        this.dstId = dstId;
        this.edgeType = edgeType;
    }

    public String getSrcId() {
        return srcId;
    }

    public String getDstId() {
        return dstId;
    }

    public EdgeType getEdgeType() {
        return edgeType;
    }

    @Override
    public String toString() {
        return "Edge{"
                + "srcId='" + srcId + '\''
                + ", dstId='" + dstId + '\''
                + ", edgeType=" + edgeType
                + '}';
    }
}