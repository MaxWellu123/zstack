package org.zstack.storage.ceph.primary.capacity;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.core.Platform;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.Q;
import org.zstack.core.db.SQL;
import org.zstack.core.db.SQLBatch;
import org.zstack.header.storage.primary.PrimaryStorageCapacityUpdaterRunnable;
import org.zstack.header.storage.primary.PrimaryStorageCapacityVO;
import org.zstack.storage.ceph.CephCapacity;
import org.zstack.storage.ceph.CephConstants;
import org.zstack.storage.ceph.CephPoolCapacity;
import org.zstack.storage.ceph.primary.*;
import org.zstack.storage.primary.PrimaryStorageCapacityUpdater;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

/**
 * Created by lining on 2021/1/22.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class OpenSourceCephPrimaryCapacityUpdater implements CephPrimaryCapacityUpdater {
    @Autowired
    private DatabaseFacade dbf;

    private static final CLogger logger = Utils.getLogger(OpenSourceCephPrimaryCapacityUpdater.class);

    @Override
    public String getCephManufacturer() {
        return CephConstants.CEPH_MANUFACTURER_OPENSOURCE;
    }

    @Override
    public void update(CephCapacity cephCapacity) {
        String fsid = cephCapacity.getFsid();
        long total = cephCapacity.getTotalCapacity();
        long avail = cephCapacity.getAvailableCapacity();
        List<CephPoolCapacity> poolCapacities = cephCapacity.getPoolCapacities();

        CephPrimaryStorageVO cephPs = SQL.New("select pri from CephPrimaryStorageVO pri where pri.fsid = :fsid",CephPrimaryStorageVO.class)
                .param("fsid", fsid)
                .find();
        if (cephPs == null) {
            return;
        }

        List<String> poolNames = new ArrayList<>();

        PrimaryStorageCapacityUpdater updater = new PrimaryStorageCapacityUpdater(cephPs.getUuid());
        updater.run(new PrimaryStorageCapacityUpdaterRunnable() {
            @Override
            public PrimaryStorageCapacityVO call(PrimaryStorageCapacityVO cap) {
                if (cap.getTotalCapacity() == 0 || cap.getAvailableCapacity() == 0) {
                    // init
                    cap.setAvailableCapacity(avail);
                }

                cap.setTotalCapacity(total);
                cap.setTotalPhysicalCapacity(total);
                cap.setAvailablePhysicalCapacity(avail);
                return cap;
            }
        });

        if (poolCapacities == null) {
            return;
        }

        new SQLBatch() {
            @Override
            protected void scripts() {
                Map<String, List<CephPoolCapacity>> caps = poolCapacities.stream().collect(groupingBy(CephPoolCapacity::getRelatedOsds));

                Set<String> osdGroups = caps.keySet();
                logger.info(String.format("ceph[%s] primary storage found osd groups %s", cephPs.getUuid(), osdGroups));

                List<CephOsdGroupVO> existedOsdGroups = Q.New(CephOsdGroupVO.class).eq(CephOsdGroupVO_.primaryStorageUuid, cephPs.getUuid()).list();
                List<CephOsdGroupVO> needDeleteOsds = existedOsdGroups.stream()
                        .filter(v -> !osdGroups.contains(v.getOsds())).collect(Collectors.toList());
                List<String> needCreateOsds = osdGroups.stream()
                        .filter(v -> existedOsdGroups.stream().noneMatch(a -> a.getOsds().equals(v))).collect(Collectors.toList());

                if (!needDeleteOsds.isEmpty()) {
                    logger.info(String.format("remove %s stale osd groups", needDeleteOsds.size()));
                    needDeleteOsds.forEach(this::remove);
                }

                List<CephOsdGroupVO> newOsdGroups = new ArrayList<>();
                for (String osds : needCreateOsds) {
                    CephOsdGroupVO vo = new CephOsdGroupVO();
                    vo.setUuid(Platform.getUuid());
                    vo.setOsds(osds);
                    vo.setPrimaryStorageUuid(cephPs.getUuid());
                    newOsdGroups.add(vo);
                }
                newOsdGroups.forEach(this::persist);

                List<CephPrimaryStoragePoolVO> pools = Q.New(CephPrimaryStoragePoolVO.class).eq(CephPrimaryStoragePoolVO_.primaryStorageUuid, cephPs.getUuid()).list();
                for (CephPrimaryStoragePoolVO poolVO : pools) {
                    if (!poolCapacities.stream().anyMatch((e) -> poolVO.getPoolName().equals(e.getName()))) {
                        if (poolNames.contains(poolVO.getPoolName())) {
                            continue;
                        }
                        poolNames.add(poolVO.getPoolName());
                        continue;
                    }

                    CephPoolCapacity poolCapacity = poolCapacities.stream()
                            .filter(e -> poolVO.getPoolName().equals(e.getName()))
                            .findAny().get();

                    CephOsdGroupVO osdGroupVO = Q.New(CephOsdGroupVO.class)
                            .eq(CephOsdGroupVO_.osds, poolCapacity.getRelatedOsds())
                            .eq(CephOsdGroupVO_.primaryStorageUuid, cephPs.getUuid())
                            .find();

                    // ceph pool related osds has changed
                    if (poolVO.getOsdGroup() == null || (poolVO.getOsdGroup() != null &&
                            !poolVO.getOsdGroup().getUuid().equals(osdGroupVO.getUuid()))) {
                        poolVO.setOsdGroup(osdGroupVO);
                    }

                    if (!poolNames.contains(poolVO.getPoolName())) {
                        poolNames.add(poolVO.getPoolName());
                    }

                    poolVO.setAvailableCapacity(poolCapacity.getAvailableCapacity());
                    poolVO.setReplicatedSize(poolCapacity.getReplicatedSize());
                    poolVO.setUsedCapacity(poolCapacity.getUsedCapacity());
                    poolVO.setTotalCapacity(poolCapacity.getTotalCapacity());
                    poolVO.setDiskUtilization(poolCapacity.getDiskUtilization());
                    poolVO.setSecurityPolicy(poolCapacity.getSecurityPolicy());
                    dbf.getEntityManager().merge(poolVO);
                }

                new CephOsdGroupCapacityHelper(cephPs.getUuid()).fillCapacityFromPool();
            }
        }.execute();
    }
}
