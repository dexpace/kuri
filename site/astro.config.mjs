// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  site: 'https://dexpace.github.io',
  base: '/kuri',
  integrations: [
    starlight({
      title: 'kuri',
      description: 'A standards-faithful URI and URL library for Kotlin Multiplatform and Java.',
      customCss: ['./src/styles/kuri-theme.css'],
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/dexpace/kuri' },
      ],
      sidebar: [
        {
          label: 'Guides',
          items: [{ autogenerate: { directory: 'guides' } }],
        },
        {
          label: 'Reference',
          items: [
            { label: 'Specification', slug: 'spec' },
            { label: 'API reference', link: '/api/', attrs: { target: '_blank', rel: 'noopener' } },
          ],
        },
      ],
    }),
  ],
});
