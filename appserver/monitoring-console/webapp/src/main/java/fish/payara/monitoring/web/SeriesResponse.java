/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2019 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.monitoring.web;

import java.math.BigInteger;
import java.util.Collection;

import fish.payara.monitoring.model.SeriesDataset;

public final class SeriesResponse {

    public static SeriesResponse[] from(Collection<SeriesDataset> sets) {
        SeriesResponse[] stats = new SeriesResponse[sets.size()];
        int i = 0;
        for (SeriesDataset set : sets) {
            stats[i++] = new SeriesResponse(set);
        }
        return stats;
    }

    public final String series;
    public final String instance;
    public final long[] points;
    public final long observedMax;
    public final long observedMin;
    public final BigInteger observedSum;
    public final int observedValues;
    public final int observedValueChanges;
    public final long observedSince;
    public final int stableCount;
    public final long stableSince;

    public SeriesResponse(SeriesDataset set) {
        this.instance = set.getInstance();
        this.series = set.getSeries().toString();
        this.points = set.points();
        this.observedMax = set.getObservedMax();
        this.observedMin = set.getObservedMin();
        this.observedSum = set.getObservedSum();
        this.observedValues = set.getObservedValues();
        this.observedValueChanges = set.getObservedValueChanges();
        this.observedSince = set.getObservedSince();
        this.stableCount = set.getStableCount();
        this.stableSince = set.getStableSince();
    }
}
