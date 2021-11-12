/*
 ***************************************************************************************
 *  Copyright (C) 2006 EsperTech, Inc. All rights reserved.                            *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 ***************************************************************************************
 */
package com.espertech.esper.common.internal.epl.historical.method.poll;

import com.espertech.esper.common.client.EventBean;
import com.espertech.esper.common.client.type.EPTypeClass;
import com.espertech.esper.common.internal.epl.expression.core.ExprEvaluatorContext;

import java.util.Map;

public class MethodConversionStrategyCollectionMap extends MethodConversionStrategyCollection {
    public final static EPTypeClass EPTYPE = new EPTypeClass(MethodConversionStrategyCollectionMap.class);

    protected EventBean getEventBean(Object value, ExprEvaluatorContext exprEvaluatorContext) {
        return exprEvaluatorContext.getEventBeanTypedEventFactory().adapterForTypedMap((Map) value, eventType);
    }
}
