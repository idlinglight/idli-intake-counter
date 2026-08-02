import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ItemForm from '../ItemForm.vue'

const metrics = [
  { id: 1, name: 'energy', canonicalUnit: 'kJ' },
  { id: 2, name: 'protein', canonicalUnit: 'g' },
]

const initial = {
  id: 1,
  name: 'protein bar',
  basisAmount: 100,
  basisUnit: 'g',
  amounts: [{ metricId: 1, amount: 2281 }],
  servings: [],
}

describe('ItemForm', () => {
  it('starts blank for creation with one empty amount row', () => {
    const wrapper = mount(ItemForm, { props: { metrics } })

    expect((wrapper.get('[data-testid="item-name"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.findAll('[data-testid="amount-row"]')).toHaveLength(1)
    expect(wrapper.get('[data-testid="item-form-submit"]').text()).toBe('Create item')
  })

  it('prefills from the initial item and emits the edited payload', async () => {
    const wrapper = mount(ItemForm, { props: { metrics, initial } })

    expect((wrapper.get('[data-testid="item-name"]').element as HTMLInputElement).value).toBe(
      'protein bar',
    )
    expect(wrapper.get('[data-testid="item-form-submit"]').text()).toBe('Save item')

    await wrapper.get('[data-testid="amount-value"]').setValue(2300)
    await wrapper.get('[data-testid="item-form"]').trigger('submit')

    expect(wrapper.emitted('submit')).toEqual([
      [{ name: 'protein bar', basisAmount: 100, basisUnit: 'g', amounts: [{ metricId: 1, amount: 2300 }] }],
    ])
  })

  it('adds rows whose metric choices exclude metrics already used', async () => {
    const wrapper = mount(ItemForm, { props: { metrics, initial } })

    await wrapper.get('[data-testid="add-amount-row"]').trigger('click')
    const selects = wrapper.findAll('[data-testid="amount-metric"]')
    expect(selects).toHaveLength(2)

    // Row 1 holds energy, so row 2 may only offer protein (plus the placeholder).
    const secondRowOptions = selects[1]!.findAll('option').map((option) => option.text())
    expect(secondRowOptions).not.toContain('energy')
    expect(secondRowOptions).toContain('protein')

    await selects[1]!.setValue(2)
    const values = wrapper.findAll('[data-testid="amount-value"]')
    await values[1]!.setValue(30)
    await wrapper.get('[data-testid="item-form"]').trigger('submit')

    expect(wrapper.emitted('submit')).toEqual([
      [
        {
          name: 'protein bar',
          basisAmount: 100,
          basisUnit: 'g',
          amounts: [
            { metricId: 1, amount: 2281 },
            { metricId: 2, amount: 30 },
          ],
        },
      ],
    ])
  })

  it('refuses to emit while an amount row is incomplete', async () => {
    const wrapper = mount(ItemForm, { props: { metrics } })

    await wrapper.get('[data-testid="item-name"]').setValue('idli')
    // Row has neither metric nor amount — jsdom happily submits anyway.
    await wrapper.get('[data-testid="item-form"]').trigger('submit')

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.text()).toContain('at least one complete amount row')
  })

  it('removing a row takes its metric out of the payload', async () => {
    const wrapper = mount(ItemForm, { props: { metrics, initial } })

    await wrapper.get('[data-testid="add-amount-row"]').trigger('click')
    const selects = wrapper.findAll('[data-testid="amount-metric"]')
    await selects[1]!.setValue(2)
    await wrapper.findAll('[data-testid="amount-value"]')[1]!.setValue(30)

    await wrapper.get('[aria-label="remove amount row 1"]').trigger('click')
    await wrapper.get('[data-testid="item-form"]').trigger('submit')

    expect(wrapper.emitted('submit')).toEqual([
      [{ name: 'protein bar', basisAmount: 100, basisUnit: 'g', amounts: [{ metricId: 2, amount: 30 }] }],
    ])
  })
})
