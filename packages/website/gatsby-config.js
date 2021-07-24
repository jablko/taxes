const parseJson = require("parse-json");

module.exports = {
  plugins: [
    {
      resolve: "gatsby-plugin-mdx",
      options: {
        rehypePlugins: [
          function () {
            this.parse = (file) =>
              parseJson(
                // https://github.com/gatsbyjs/gatsby/blob/58d5a2c6955f1263dd5f2b28369a9c177485d36a/packages/gatsby-plugin-mdx/utils/gen-mdx.js#L98
                file.toString().replace(/export const _frontmatter = {}/, ""),
                file.path
              );
            this.run = (tree, file, callback) => {
              callback(null, tree, file);
            };
          },
        ],
      },
    },
  ],
};
