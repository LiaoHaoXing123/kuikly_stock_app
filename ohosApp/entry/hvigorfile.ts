import { hapTasks } from '@ohos/hvigor-ohos-plugin';
import { kuiklyCompilePlugin, kuiklyCopyAssetsPlugin } from 'kuikly-ohos-compile-plugin';

export default {
    system: hapTasks,
    plugins:[kuiklyCompilePlugin()]
}
