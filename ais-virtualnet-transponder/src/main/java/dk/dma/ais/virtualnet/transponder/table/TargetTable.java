/* Copyright (c) 2011 Danish Maritime Authority
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this library.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dma.ais.virtualnet.transponder.table;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.jcip.annotations.ThreadSafe;
import dk.dma.ais.message.AisMessage;
import dk.dma.ais.message.AisStaticCommon;
import dk.dma.ais.message.IVesselPositionMessage;

/**
 * Simple table of AIS vessel targets
 */
@ThreadSafe
public class TargetTable {
    
    private final ConcurrentHashMap<Integer, TargetTableEntry> targets = new ConcurrentHashMap<>();
    
    public TargetTable() {
        
    }
    
    public void update(AisMessage message) {
        if (!(message instanceof IVesselPositionMessage) && !(message instanceof AisStaticCommon)) {
            return;
        }
        TargetTableEntry entry = new TargetTableEntry();
        entry = targets.putIfAbsent(message.getUserId(), entry);
        // TODO what is returned (null?)
        entry.update(message);
    }
    
    public Map<Integer, TargetTableEntry> allTargets() {
        return Collections.unmodifiableMap(targets);
    }

}