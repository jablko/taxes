import parseJson from "parse-json";
import { VFile } from "vfile";
import { Plugin, Processor } from "unified";

const attacher: Plugin = function () {
  this.Parser = class {
    constructor(readonly text: string, readonly file: VFile) {}

    parse() {
      return parseJson(
        // https://github.com/gatsbyjs/gatsby/blob/4a765b5c62208d58f0bd7fd59558160c0b9feed3/packages/gatsby-plugin-mdx/utils/gen-mdx.js#L98
        this.text.replace("export const _frontmatter = {}", ""),
        this.file.path
      );
    }
  };
  // @ts-expect-error
  // https://github.com/gatsbyjs/gatsby/blob/ae2e2de0e4da03d7a0a662a9cf3af15d26c1e741/packages/gatsby-plugin-mdx/loaders/mdx-loader.js#L81
  this.Parser.prototype.blockTokenizers = {};
  // @ts-expect-error
  // https://github.com/gatsbyjs/gatsby/blob/ae2e2de0e4da03d7a0a662a9cf3af15d26c1e741/packages/gatsby-plugin-mdx/loaders/mdx-loader.js#L83
  this.Parser.prototype.blockMethods = [];
  // https://github.com/unifiedjs/unified/blob/2323d38e2c74708f5a453ebfea42f80e0454a435/lib/index.js#L299
  this.run = ((tree, file, callback) => {
    if (!callback) return Promise.resolve(tree);
    callback(null, tree, /*new VFile*/ file as VFile);
  }) as Processor["run"];
};
export = attacher;
